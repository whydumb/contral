package com.kAIS.KAIMyEntity.webots;

import com.kAIS.KAIMyEntity.coordinator.MotionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * WebotsController - 마인크래프트 입력 → C++ 로봇 서버 제어
 *
 * 역할:
 *  - Minecraft WASD + 마우스 에임을 읽어서
 *  - C++ 서버로 set_walk, set_head, set_joint 명령 전송
 *  - 세부 관절 제어 (팔, 다리, 머리)
 *  - 머리 360도 회전 방지 (기준점 기반 상대 각도)
 * 
 * walk.cpp 호환:
 *  - DARWIN_JOINT_LIMITS[20] 배열로 관절 한계 관리
 *  - FILTER_ALPHA = 0.015 (과행동 방지)
 *  - 레이트 리미팅 (50 updates/sec)
 * 
 * Coordinator 연동:
 *  - 모션/걷기/관절 명령은 Lock 확인 후 전송
 *  - blink/track 명령은 Lock 무관하게 항상 허용
 */
public class WebotsController {
    private static final Logger LOGGER = LogManager.getLogger();
    private static WebotsController instance;

    // ==================== Mode enum ====================
    public enum Mode {
        WEBOTS,
        ROBOTLISTENER
    }

    // ==================== 네트워크 설정 ====================
    private final HttpClient httpClient;
    private String serverIp;
    private int serverPort;
    private String serverUrl;

    private volatile boolean connected = false;

    // ==================== RobotListener 관련 ====================
    private boolean robotListenerEnabled = false;
    
    // ==================== Coordinator 관련 ====================
    private static final String OWNER_ID = "contral";
    private final MotionCoordinator coordinator;
    private boolean hasLock = false;
    private int lockBlockedCount = 0;
    
    // ==================== 통계 (walk.cpp 호환) ====================
    private int walkSent = 0;
    private int headSent = 0;
    private int jointSent = 0;
    private int errors = 0;
    private int droppedRequests = 0;    // 레이트 리미팅으로 버린 요청
    private int rangeViolations = 0;    // 범위 초과 감지

    // ==================== WASD 이전 상태 ====================
    private boolean lastF = false, lastB = false, lastL = false, lastR = false;
    private boolean lastJump = false, lastSneak = false;
    private boolean lastAttack = false;

    // ==================== 머리(마우스) - 360도 회전 방지 ====================
    private float lastYaw = 0.0f;
    private float lastPitch = 0.0f;
    
    // 기준점 (센터링용) - 라디안
    private float headYawOffset = 0.0f;
    private float headPitchOffset = 0.0f;
    
    // 현재 로봇 머리 위치 (부드러운 보간용)
    private float currentNeckRad = 0.0f;
    private float currentHeadRad = 0.0f;

    private boolean forceWalkUpdate = true;
    private boolean forceHeadUpdate = true;

    // ==================== walk.cpp 호환: 과행동 방지 필터 ====================
    
    // 마우스 민감도 (도 단위)
    private static final float YAW_SENSITIVITY_DEG = 0.57f;
    private static final float PITCH_SENSITIVITY_DEG = 0.57f;

    // walk.cpp와 동일한 필터 상수 (과행동 방지)
    private static final float FILTER_ALPHA = 0.015f;  // C++: const double FILTER_ALPHA = 0.015;
    
    // 레이트 리미팅 (walk.cpp: MAX_UPDATES_PER_SECOND = 50)
    private static final int MAX_UPDATES_PER_SECOND = 50;
    private static final long MIN_UPDATE_INTERVAL_MS = 1000 / MAX_UPDATES_PER_SECOND;  // 20ms
    private long lastJointUpdateTime = 0;
    
    // 안전 마진
    private static final float SAFETY_MARGIN = 0.05f;  // ~3°
    
    // 틱당 최대 변화량 (급격한 변화 방지)
    private static final float MAX_VELOCITY_RAD = 0.05f;  // 더 부드럽게 (~3°/tick)

    // ==================== DARWIN_JOINT_LIMITS (walk.cpp 동일) ====================
    // 인덱스: 0-5 팔, 6-9 골반, 10-17 다리, 18-19 머리
    private static final float[][] DARWIN_JOINT_LIMITS = {
        // 팔 (Arms)
        {-1.57f, 0.52f},   // 0: ShoulderR - Pitch ±90°/30°
        {-1.57f, 0.52f},   // 1: ShoulderL - Pitch ±90°/30°
        {-0.68f, 2.30f},   // 2: ArmUpperR - Roll -39°~131°
        {-2.25f, 0.77f},   // 3: ArmUpperL - Roll -129°~44°
        {-1.57f, -0.10f},  // 4: ArmLowerR - Elbow -90°~-5.7° (항상 음수!)
        {-1.57f, -0.10f},  // 5: ArmLowerL - Elbow -90°~-5.7° (항상 음수!)
        
        // 골반 (Pelvis)
        {-1.047f, 1.047f}, // 6: PelvYR - Yaw ±60°
        {-0.69f, 2.50f},   // 7: PelvYL - Yaw -39°~143°
        {-1.01f, 1.01f},   // 8: PelvR - Roll ±58°
        {-0.35f, 0.35f},   // 9: PelvL - Roll ±20°
        
        // 다리 (Legs)
        {-2.50f, 0.87f},   // 10: LegUpperR - Hip Pitch -143°~50°
        {-2.50f, 0.87f},   // 11: LegUpperL - Hip Pitch -143°~50°
        {-0.35f, 0.35f},   // 12: LegLowerR - Hip Roll ±20°
        {-0.35f, 0.35f},   // 13: LegLowerL - Hip Roll ±20°
        
        // 발목 (Ankle)
        {-0.87f, 0.87f},   // 14: AnkleR - Pitch ±50°
        {-1.39f, 1.22f},   // 15: AnkleL - Pitch -80°~70°
        {-0.87f, 0.87f},   // 16: FootR - Roll ±50°
        {-0.87f, 0.87f},   // 17: FootL - Roll ±50°
        
        // 머리 (Head) - 360도 회전 방지 핵심!
        {-1.57f, 1.57f},   // 18: Neck - Pan ±90°
        {-0.52f, 0.52f}    // 19: Head - Tilt ±30°
    };
    
    private static final String[] MOTOR_NAMES = {
        "ShoulderR", "ShoulderL", "ArmUpperR", "ArmUpperL",
        "ArmLowerR", "ArmLowerL", "PelvYR", "PelvYL",
        "PelvR", "PelvL", "LegUpperR", "LegUpperL",
        "LegLowerR", "LegLowerL", "AnkleR", "AnkleL",
        "FootR", "FootL", "Neck", "Head"
    };
    
    private static final int NMOTORS = 20;

    // ==================== walk.cpp 호환: 관절 상태 배열 ====================
    // targetPositions[NMOTORS] - 목표 위치
    private final float[] targetPositions = new float[NMOTORS];
    // filteredPositions[NMOTORS] - 필터링된 현재 위치 (과행동 방지)
    private final float[] filteredPositions = new float[NMOTORS];
    // 이전 전송 값 (네트워크 최적화)
    private final float[] lastSentPositions = new float[NMOTORS];
    
    // 펀치/웨이브 쿨다운 및 자동 복귀
    private int punchCooldown = 0;
    private boolean lastPunchRight = false;
    private int armResetTimer = 0;  // 펀치/웨이브 후 자동 복귀 타이머
    private static final int ARM_RESET_DELAY = 15;  // 0.75초 후 복귀
    
    // HTTP 전송 최적화
    private static final float SEND_THRESHOLD = 0.005f;  // 변화 임계값 (~0.3°)

    // ==================== 팔 관절 한계 (DARWIN_JOINT_LIMITS 기반) ====================
    private static final float SHOULDER_MIN = -1.57f;   // -90°
    private static final float SHOULDER_MAX = 0.52f;    // +30°
    private static final float ARM_UPPER_R_MIN = -0.68f;
    private static final float ARM_UPPER_R_MAX = 2.30f;
    private static final float ARM_UPPER_L_MIN = -2.25f;
    private static final float ARM_UPPER_L_MAX = 0.77f;
    private static final float ARM_LOWER_MIN = -1.57f;
    private static final float ARM_LOWER_MAX = -0.10f;
    
    // ==================== 머리 관절 한계 (360도 회전 방지) ====================
    private static final float NECK_MIN = -1.57f;  // -90°
    private static final float NECK_MAX = 1.57f;   // +90°
    private static final float HEAD_MIN = -0.52f;  // -30°
    private static final float HEAD_MAX = 0.52f;   // +30°
    
    // ==================== 팔 보간 (smoothing factor) ====================
    private static final float SMOOTHING = 0.15f;  // 15% per tick
    
    // ==================== 팔 현재/목표 값 ====================
    private float targetShoulderR = 0f, targetShoulderL = 0f;
    private float targetArmUpperR = 0f, targetArmUpperL = 0f;
    private float targetArmLowerR = -0.15f, targetArmLowerL = -0.15f;
    
    private float currentShoulderR = 0f, currentShoulderL = 0f;
    private float currentArmUpperR = 0f, currentArmUpperL = 0f;
    private float currentArmLowerR = -0.15f, currentArmLowerL = -0.15f;
    
    // ==================== 이전 전송 값 (네트워크 최적화) ====================
    private float lastSentNeckRad = Float.NaN;
    private float lastSentHeadRad = Float.NaN;
    private float lastSentShoulderR = Float.NaN, lastSentShoulderL = Float.NaN;
    private float lastSentArmUpperR = Float.NaN, lastSentArmUpperL = Float.NaN;
    private float lastSentArmLowerR = Float.NaN, lastSentArmLowerL = Float.NaN;

    // ==================== 생성자 & 싱글톤 ====================

    private WebotsController(String ip, int port) {
        this.serverIp = ip;
        this.serverPort = port;
        this.serverUrl = String.format("http://%s:%d", ip, port);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
        
        this.coordinator = MotionCoordinator.getInstance();

        LOGGER.info("WebotsController initialized: {} (coordinator: {})", serverUrl, OWNER_ID);
    }

    public static WebotsController getInstance() {
        if (instance == null) {
            try {
                WebotsConfigScreen.Config config = WebotsConfigScreen.Config.getInstance();
                instance = new WebotsController(config.getLastIp(), config.getLastPort());
            } catch (Exception e) {
                LOGGER.warn("Failed to load config, using defaults", e);
                instance = new WebotsController("localhost", 8080);
            }
        }
        return instance;
    }

    public static WebotsController getInstance(String ip, int port) {
        if (instance != null) {
            if (!instance.serverIp.equals(ip) || instance.serverPort != port) {
                LOGGER.info("Recreating WebotsController: {}:{}", ip, port);
                instance.shutdown();
                instance = new WebotsController(ip, port);
                try {
                    WebotsConfigScreen.Config.getInstance().update(ip, port);
                } catch (Exception e) {
                    LOGGER.warn("Failed to save config", e);
                }
            }
        } else {
            instance = new WebotsController(ip, port);
            try {
                WebotsConfigScreen.Config.getInstance().update(ip, port);
            } catch (Exception e) {
                LOGGER.warn("Failed to save config", e);
            }
        }
        return instance;
    }

    // ==================== Mode 관련 ====================

    public Mode getMode() {
        return robotListenerEnabled ? Mode.ROBOTLISTENER : Mode.WEBOTS;
    }

    // ==================== RobotListener on/off ====================

    public void enableRobotListener(boolean enable) {
        if (enable) {
            MotionCoordinator.AcquireResult result = coordinator.acquire(
                OWNER_ID, 
                "WASD + Mouse Manual Control", 
                0
            );
            
            if (!result.success) {
                LOGGER.warn("Failed to acquire lock: {} (owner: {})", result.message, result.currentOwner);
            } else {
                hasLock = true;
                LOGGER.info("Lock acquired for {}", OWNER_ID);
            }
            
            this.robotListenerEnabled = true;
            primeRobotListenerInputs();
            LOGGER.info("RobotListener mode ENABLED (hasLock: {})", hasLock);
        } else {
            if (hasLock) {
                MotionCoordinator.ReleaseResult result = coordinator.release(OWNER_ID);
                if (result.success) {
                    LOGGER.info("Lock released for {}", OWNER_ID);
                }
                hasLock = false;
            }
            
            sendStopAll();
            resetToStandPose();
            forceWalkUpdate = true;
            forceHeadUpdate = true;
            this.robotListenerEnabled = false;
            LOGGER.info("RobotListener mode DISABLED");
        }
    }

    public boolean isRobotListenerEnabled() {
        return robotListenerEnabled;
    }

    // ==================== 머리 센터링 ====================
    
    /**
     * 현재 마인크래프트 카메라 위치를 기준점(0,0)으로 설정
     * UI에서 'Center Head' 버튼 클릭 시 호출
     */
    public void centerHead() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null) {
            headYawOffset = (float) Math.toRadians(player.getYRot());
            headPitchOffset = (float) Math.toRadians(player.getXRot());
            
            currentNeckRad = 0f;
            currentHeadRad = 0f;
            
            LOGGER.info("Head centered at MC Yaw={}, Pitch={}", player.getYRot(), player.getXRot());
        }
    }
    
    /**
     * 머리를 정면으로 리셋
     */
    public void resetHead() {
        currentNeckRad = 0f;
        currentHeadRad = 0f;
        
        sendJointCommand(18, 0f);  // Neck
        sendJointCommand(19, 0f);  // Head
        
        LOGGER.info("Head reset to center");
    }

    // ==================== 팔 프리셋 ====================
    
    /**
     * 기본 서있는 자세로 팔 리셋
     */
    public void resetToStandPose() {
        targetShoulderR = 0f;
        targetShoulderL = 0f;
        targetArmUpperR = 0f;
        targetArmUpperL = 0f;
        targetArmLowerR = -0.15f;
        targetArmLowerL = -0.15f;
        
        LOGGER.info("Arms reset to stand pose");
    }
    
    /**
     * T-Pose
     */
    public void setTPose() {
        targetShoulderR = 0.3f;
        targetShoulderL = 0.3f;
        targetArmUpperR = 1.57f;   // 오른팔 옆으로
        targetArmUpperL = -1.57f;  // 왼팔 옆으로
        targetArmLowerR = -0.15f;
        targetArmLowerL = -0.15f;
        
        LOGGER.info("Set T-Pose");
    }
    
    /**
     * 가드 자세
     */
    public void setGuardPose() {
        targetShoulderR = -0.5f;
        targetShoulderL = -0.5f;
        targetArmUpperR = 0.5f;
        targetArmUpperL = -0.5f;
        targetArmLowerR = -1.0f;
        targetArmLowerL = -1.0f;
        
        LOGGER.info("Set Guard pose");
    }
    
    /**
     * 오른팔 펀치
     */
    public void punchRight() {
        targetShoulderR = -1.0f;
        targetArmUpperR = 0f;
        targetArmLowerR = -0.2f;
        armResetTimer = ARM_RESET_DELAY;  // 자동 복귀 예약
        
        LOGGER.info("Right punch!");
    }
    
    /**
     * 왼팔 펀치
     */
    public void punchLeft() {
        targetShoulderL = -1.0f;
        targetArmUpperL = 0f;
        targetArmLowerL = -0.2f;
        armResetTimer = ARM_RESET_DELAY;  // 자동 복귀 예약
        
        LOGGER.info("Left punch!");
    }
    
    /**
     * 손 흔들기
     */
    public void waveHand() {
        targetShoulderR = 0.3f;
        targetArmUpperR = 1.5f;
        targetArmLowerR = -0.5f;
        armResetTimer = ARM_RESET_DELAY * 2;  // 웨이브는 좀 더 길게 유지
        
        LOGGER.info("Wave hand");
    }

    // ==================== 통계 Getter ====================

    public int getWalkSent() { return walkSent; }
    public int getHeadSent() { return headSent; }
    public int getJointSent() { return jointSent; }
    public int getErrors() { return errors; }

    public void printStats() {
        LOGGER.info("=== WebotsController Stats ===");
        LOGGER.info("Connected: {}", connected);
        LOGGER.info("Mode: {}", getMode());
        LOGGER.info("Walk: {} | Head: {} | Joint: {} | Errors: {}", walkSent, headSent, jointSent, errors);
        LOGGER.info("Has Lock: {} | Blocked: {}", hasLock, lockBlockedCount);
        
        MotionCoordinator.LockStatus status = coordinator.getStatus();
        if (status.locked) {
            LOGGER.info("Coordinator Lock: {} (task: {}, elapsed: {}ms)", 
                       status.owner, status.taskDescription, status.elapsedMs);
        } else {
            LOGGER.info("Coordinator Lock: FREE");
        }
    }

    // ==================== Joint Control (직접 관절 제어) ====================

    /**
     * 인덱스로 관절 제어 (C++ 서버 호환)
     * index 0-5: 팔, 6-17: 다리/골반, 18: Neck, 19: Head
     */
    public void setJoint(int index, float value) {
        if (!canSendMotionCommand()) {
            lockBlockedCount++;
            return;
        }
        
        sendJointCommand(index, value);
    }
    
    /**
     * 이름으로 관절 제어 (기존 호환)
     */
    public void setJoint(String jointName, float value) {
        Integer index = getJointIndex(jointName);
        if (index != null) {
            setJoint(index, value);
        }
    }
    
    private Integer getJointIndex(String name) {
        return switch (name) {
            case "head_pan", "Neck" -> 18;
            case "head_tilt", "Head" -> 19;
            case "r_sho_pitch", "ShoulderR" -> 0;
            case "l_sho_pitch", "ShoulderL" -> 1;
            case "r_sho_roll", "ArmUpperR" -> 2;
            case "l_sho_roll", "ArmUpperL" -> 3;
            case "r_el", "ArmLowerR" -> 4;
            case "l_el", "ArmLowerL" -> 5;
            case "r_hip_yaw", "PelvYR" -> 6;
            case "l_hip_yaw", "PelvYL" -> 7;
            case "r_hip_roll", "PelvR" -> 8;
            case "l_hip_roll", "PelvL" -> 9;
            case "r_hip_pitch", "LegUpperR" -> 10;
            case "l_hip_pitch", "LegUpperL" -> 11;
            case "r_knee", "LegLowerR" -> 12;
            case "l_knee", "LegLowerL" -> 13;
            case "r_ank_pitch", "AnkleR" -> 14;
            case "l_ank_pitch", "AnkleL" -> 15;
            case "r_ank_roll", "FootR" -> 16;
            case "l_ank_roll", "FootL" -> 17;
            default -> null;
        };
    }

    // ==================== 매 틱 호출 ====================

    public void tick() {
        if (!robotListenerEnabled) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        LocalPlayer player = mc.player;
        if (player == null) return;
        
        boolean canSend = canSendMotionCommand();

        // ========== 1. WASD 처리 ==========
        boolean f = mc.options.keyUp.isDown();
        boolean b = mc.options.keyDown.isDown();
        boolean l = mc.options.keyLeft.isDown();
        boolean r = mc.options.keyRight.isDown();

        boolean walkChanged = forceWalkUpdate || f != lastF || b != lastB || l != lastL || r != lastR;
        if (walkChanged) {
            if (canSend) {
                sendWalkCommand(f, b, l, r);
            } else {
                lockBlockedCount++;
            }
            lastF = f; lastB = b; lastL = l; lastR = r;
            forceWalkUpdate = false;
        }

        // ========== 2. 머리 (마우스) - 360도 방지 ==========
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        float yawDelta = Math.abs(yaw - lastYaw);
        float pitchDelta = Math.abs(pitch - lastPitch);

        boolean headChanged = forceHeadUpdate
                || yawDelta > YAW_SENSITIVITY_DEG
                || pitchDelta > PITCH_SENSITIVITY_DEG;

        if (headChanged) {
            if (canSend) {
                updateHeadFromMinecraft(yaw, pitch);
            } else {
                lockBlockedCount++;
            }
            lastYaw = yaw;
            lastPitch = pitch;
            forceHeadUpdate = false;
        }
        
        // 부드러운 머리 보간 적용 후 전송
        if (canSend) {
            tickHeadSmoothing();
        }

        // ========== 3. 점프/웅크리기 ==========
        boolean jump = mc.options.keyJump.isDown();
        boolean sneak = mc.options.keyShift.isDown();
        
        if (jump && !lastJump) {
            // 점프 준비 자세 (간단한 모션)
            // 실제로는 C++ 서버에 motion 명령 전송
            LOGGER.debug("Jump pressed");
        }
        lastJump = jump;
        
        if (sneak && !lastSneak) {
            LOGGER.debug("Sneak pressed");
        }
        lastSneak = sneak;

        // ========== 4. 공격 (좌클릭) → 펀치 ==========
        boolean attack = mc.options.keyAttack.isDown();
        
        if (punchCooldown > 0) {
            punchCooldown--;
        }
        
        if (attack && !lastAttack && punchCooldown == 0 && canSend) {
            if (lastPunchRight) {
                punchLeft();
            } else {
                punchRight();
            }
            lastPunchRight = !lastPunchRight;
            punchCooldown = 10;  // 0.5초 쿨다운
        }
        lastAttack = attack;
        
        // ========== 5. 팔 부드러운 보간 ==========
        if (canSend) {
            tickArmSmoothing();
        }
        
        // ========== 6. 펀치/웨이브 후 자동 복귀 ==========
        if (armResetTimer > 0) {
            armResetTimer--;
            if (armResetTimer == 0) {
                resetToStandPose();
                LOGGER.debug("Arms auto-reset to stand pose");
            }
        }
    }
    
    /**
     * 머리 부드러운 보간 + C++ 서버 전송 (변경 시에만)
     */
    private void tickHeadSmoothing() {
        // 변경이 있을 때만 전송 (네트워크 최적화)
        if (shouldSend(currentNeckRad, lastSentNeckRad)) {
            sendJointCommand(18, currentNeckRad);
            lastSentNeckRad = currentNeckRad;
        }
        if (shouldSend(currentHeadRad, lastSentHeadRad)) {
            sendJointCommand(19, currentHeadRad);
            lastSentHeadRad = currentHeadRad;
        }
    }
    
    /**
     * 전송 필요 여부 체크 (임계값 이상 변경 시 true)
     */
    private boolean shouldSend(float current, float lastSent) {
        if (Float.isNaN(lastSent)) return true;  // 첫 전송
        return Math.abs(current - lastSent) > SEND_THRESHOLD;
    }
    
    /**
     * 팔 부드러운 보간 + C++ 서버 전송 (변경 시에만)
     */
    private void tickArmSmoothing() {
        // 부드러운 보간
        currentShoulderR = smoothInterpolate(currentShoulderR, targetShoulderR);
        currentShoulderL = smoothInterpolate(currentShoulderL, targetShoulderL);
        currentArmUpperR = smoothInterpolate(currentArmUpperR, targetArmUpperR);
        currentArmUpperL = smoothInterpolate(currentArmUpperL, targetArmUpperL);
        currentArmLowerR = smoothInterpolate(currentArmLowerR, targetArmLowerR);
        currentArmLowerL = smoothInterpolate(currentArmLowerL, targetArmLowerL);
        
        // 범위 클램핑
        currentShoulderR = clamp(currentShoulderR, SHOULDER_MIN + SAFETY_MARGIN, SHOULDER_MAX - SAFETY_MARGIN);
        currentShoulderL = clamp(currentShoulderL, SHOULDER_MIN + SAFETY_MARGIN, SHOULDER_MAX - SAFETY_MARGIN);
        currentArmUpperR = clamp(currentArmUpperR, ARM_UPPER_R_MIN + SAFETY_MARGIN, ARM_UPPER_R_MAX - SAFETY_MARGIN);
        currentArmUpperL = clamp(currentArmUpperL, ARM_UPPER_L_MIN + SAFETY_MARGIN, ARM_UPPER_L_MAX - SAFETY_MARGIN);
        currentArmLowerR = clamp(currentArmLowerR, ARM_LOWER_MIN + SAFETY_MARGIN, ARM_LOWER_MAX - SAFETY_MARGIN);
        currentArmLowerL = clamp(currentArmLowerL, ARM_LOWER_MIN + SAFETY_MARGIN, ARM_LOWER_MAX - SAFETY_MARGIN);
        
        // C++ 서버로 전송 (변경 시에만 - 네트워크 최적화)
        if (shouldSend(currentShoulderR, lastSentShoulderR)) {
            sendJointCommand(0, currentShoulderR);
            lastSentShoulderR = currentShoulderR;
        }
        if (shouldSend(currentShoulderL, lastSentShoulderL)) {
            sendJointCommand(1, currentShoulderL);
            lastSentShoulderL = currentShoulderL;
        }
        if (shouldSend(currentArmUpperR, lastSentArmUpperR)) {
            sendJointCommand(2, currentArmUpperR);
            lastSentArmUpperR = currentArmUpperR;
        }
        if (shouldSend(currentArmUpperL, lastSentArmUpperL)) {
            sendJointCommand(3, currentArmUpperL);
            lastSentArmUpperL = currentArmUpperL;
        }
        if (shouldSend(currentArmLowerR, lastSentArmLowerR)) {
            sendJointCommand(4, currentArmLowerR);
            lastSentArmLowerR = currentArmLowerR;
        }
        if (shouldSend(currentArmLowerL, lastSentArmLowerL)) {
            sendJointCommand(5, currentArmLowerL);
            lastSentArmLowerL = currentArmLowerL;
        }
    }
    
    private float smoothInterpolate(float current, float target) {
        float delta = target - current;
        delta = clamp(delta, -MAX_VELOCITY_RAD, MAX_VELOCITY_RAD);
        return current + delta * SMOOTHING;
    }
    
    /**
     * 마인크래프트 카메라 각도를 로봇 머리 각도로 변환
     * 360도 회전 방지: 기준점에서의 상대 각도 사용
     */
    private void updateHeadFromMinecraft(float mcYawDeg, float mcPitchDeg) {
        // 라디안 변환
        float yawRad = (float) Math.toRadians(mcYawDeg);
        float pitchRad = (float) Math.toRadians(mcPitchDeg);
        
        // 기준점에서의 상대 각도 계산
        float relativeYaw = normalizeAngle(yawRad - headYawOffset);
        float relativePitch = pitchRad - headPitchOffset;
        
        // 안전 범위로 클램핑 (±90°, ±30°)
        float targetNeck = clamp(relativeYaw, NECK_MIN + SAFETY_MARGIN, NECK_MAX - SAFETY_MARGIN);
        float targetHead = clamp(-relativePitch, HEAD_MIN + SAFETY_MARGIN, HEAD_MAX - SAFETY_MARGIN);
        
        // 급격한 변화 방지 (틱당 최대 변화량)
        float deltaNeck = targetNeck - currentNeckRad;
        float deltaHead = targetHead - currentHeadRad;
        
        deltaNeck = clamp(deltaNeck, -MAX_VELOCITY_RAD, MAX_VELOCITY_RAD);
        deltaHead = clamp(deltaHead, -MAX_VELOCITY_RAD, MAX_VELOCITY_RAD);
        
        // 부드러운 보간
        currentNeckRad += deltaNeck * SMOOTHING;
        currentHeadRad += deltaHead * SMOOTHING;
        
        // 최종 안전 클램핑
        currentNeckRad = clamp(currentNeckRad, NECK_MIN, NECK_MAX);
        currentHeadRad = clamp(currentHeadRad, HEAD_MIN, HEAD_MAX);
    }
    
    /**
     * Heartbeat 전송
     */
    public void sendHeartbeat() {
        if (hasLock) {
            MotionCoordinator.HeartbeatResult result = coordinator.heartbeat(OWNER_ID);
            if (!result.success) {
                LOGGER.warn("Heartbeat failed: {} - releasing lock", result.message);
                hasLock = false;
            }
        }
    }
    
    public boolean tryReacquireLock() {
        if (robotListenerEnabled && !hasLock) {
            MotionCoordinator.AcquireResult result = coordinator.acquire(
                OWNER_ID, 
                "WASD + Mouse Manual Control", 
                0
            );
            if (result.success) {
                hasLock = true;
                LOGGER.info("Lock reacquired for {}", OWNER_ID);
                return true;
            }
        }
        return false;
    }
    
    private boolean canSendMotionCommand() {
        return coordinator.canExecute(OWNER_ID);
    }

    private void primeRobotListenerInputs() {
        forceWalkUpdate = true;
        forceHeadUpdate = true;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            lastF = lastB = lastL = lastR = false;
            lastYaw = 0.0f;
            lastPitch = 0.0f;
            return;
        }

        lastF = mc.options.keyUp.isDown();
        lastB = mc.options.keyDown.isDown();
        lastL = mc.options.keyLeft.isDown();
        lastR = mc.options.keyRight.isDown();

        LocalPlayer player = mc.player;
        if (player != null) {
            lastYaw = player.getYRot();
            lastPitch = player.getXRot();
            
            // 현재 위치를 기준점으로 설정
            centerHead();
        }
    }

    // ==================== HTTP 명령 전송 ====================

    private void sendWalkCommand(boolean f, boolean b, boolean l, boolean r) {
        String url = String.format(
                "%s/?command=set_walk&f=%d&b=%d&l=%d&r=%d",
                serverUrl,
                f ? 1 : 0, b ? 1 : 0, l ? 1 : 0, r ? 1 : 0
        );

        sendAsyncDirect(url).thenAccept(success -> {
            if (success) walkSent++;
            else errors++;
        });
    }

    private void sendJointCommand(int index, float value) {
        String url = String.format(
                "%s/?command=set_joint&index=%d&value=%.4f",
                serverUrl, index, value
        );

        sendAsyncDirect(url).thenAccept(success -> {
            if (success) jointSent++;
            else errors++;
        });
    }

    private void sendStopAll() {
        String url = String.format("%s/?command=stop_all", serverUrl);
        sendAsyncDirect(url);
    }

    private CompletableFuture<Boolean> sendAsyncDirect(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(200))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> {
                    boolean success = (response.statusCode() == 200);
                    connected = success;
                    return success;
                })
                .exceptionally(e -> {
                    connected = false;
                    errors++;
                    return false;
                });
    }

    // ==================== 유틸리티 ====================

    public void reconnect(String ip, int port) {
        LOGGER.info("Reconnecting to {}:{}", ip, port);
        this.serverIp = ip;
        this.serverPort = port;
        this.serverUrl = String.format("http://%s:%d", ip, port);
        this.connected = false;

        try {
            WebotsConfigScreen.Config.getInstance().update(ip, port);
        } catch (Exception e) {
            LOGGER.warn("Failed to save config", e);
        }
    }

    public boolean isConnected() { return connected; }
    public String getRobotAddress() { return String.format("%s:%d", serverIp, serverPort); }
    
    public boolean hasLock() { return hasLock; }
    public int getLockBlockedCount() { return lockBlockedCount; }
    public MotionCoordinator.LockStatus getCoordinatorStatus() { return coordinator.getStatus(); }
    public String getOwnerId() { return OWNER_ID; }

    public void shutdown() {
        LOGGER.info("Shutting down WebotsController...");
        if (robotListenerEnabled) {
            sendStopAll();
        }
        if (hasLock) {
            coordinator.release(OWNER_ID);
            hasLock = false;
        }
        LOGGER.info("Shutdown complete");
    }

    /**
     * 각도를 -PI ~ PI 범위로 정규화
     */
    private static float normalizeAngle(float angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (Math.min(v, max));
    }
}
