package com.kAIS.KAIMyEntity.webots;

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
 * RobotListener 전용 컨트롤러
 *
 * 역할:
 *  - Minecraft WASD + 마우스 에임을 읽어서
 *  - RobotListener HTTP 서버로 set_walk, set_head 명령 전송
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
    
    // ==================== 통계 ====================
    private int walkSent = 0;
    private int headSent = 0;
    private int errors = 0;

    // WASD 이전 상태
    private boolean lastF = false, lastB = false, lastL = false, lastR = false;

    // 머리(마우스) 이전 상태
    private float lastYaw = 0.0f;
    private float lastPitch = 0.0f;

    private boolean forceWalkUpdate = true;
    private boolean forceHeadUpdate = true;

    // 민감도 (도 단위 기준)
    private static final float YAW_SENSITIVITY_DEG = 0.57f;
    private static final float PITCH_SENSITIVITY_DEG = 0.57f;

    // 모터 범위 (rad)
    private static final float NECK_MIN = -1.57f;
    private static final float NECK_MAX = 1.57f;
    private static final float HEAD_MIN = -0.52f;
    private static final float HEAD_MAX = 0.52f;

    // ==================== 생성자 & 싱글톤 ====================

    private WebotsController(String ip, int port) {
        this.serverIp = ip;
        this.serverPort = port;
        this.serverUrl = String.format("http://%s:%d", ip, port);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();

        LOGGER.info("WebotsController initialized: {}", serverUrl);
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
                    WebotsConfigScreen.Config config = WebotsConfigScreen.Config.getInstance();
                    config.update(ip, port);
                } catch (Exception e) {
                    LOGGER.warn("Failed to save config", e);
                }
            }
        } else {
            instance = new WebotsController(ip, port);
            try {
                WebotsConfigScreen.Config config = WebotsConfigScreen.Config.getInstance();
                config.update(ip, port);
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
        this.robotListenerEnabled = enable;
        if (enable) {
            primeRobotListenerInputs();
            LOGGER.info("RobotListener mode ENABLED");
        } else {
            sendStopAll();
            forceWalkUpdate = true;
            forceHeadUpdate = true;
            LOGGER.info("RobotListener mode DISABLED");
        }
    }

    public boolean isRobotListenerEnabled() {
        return robotListenerEnabled;
    }

    // ==================== 통계 Getter ====================

    public int getWalkSent() {
        return walkSent;
    }

    public int getHeadSent() {
        return headSent;
    }

    public int getErrors() {
        return errors;
    }

    public void printStats() {
        LOGGER.info("=== WebotsController Stats ===");
        LOGGER.info("Connected: {}", connected);
        LOGGER.info("Mode: {}", getMode());
        LOGGER.info("Walk commands sent: {}", walkSent);
        LOGGER.info("Head commands sent: {}", headSent);
        LOGGER.info("Errors: {}", errors);
    }

    // ==================== Joint Control (Webots mode) ====================

    public void setJoint(String jointName, float value) {
        if (!connected) return;
        
        String url = String.format("%s/?command=set_joint&name=%s&value=%.4f", 
                serverUrl, jointName, value);
        
        sendAsyncDirect(url).thenAccept(success -> {
            if (!success) {
                errors++;
            }
        });
    }

    // ==================== 매 틱 호출 (WASD + 마우스 읽어서 전송) ====================

    public void tick() {
        if (!robotListenerEnabled) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        LocalPlayer player = mc.player;
        if (player == null) return;

        boolean f = mc.options.keyUp.isDown();
        boolean b = mc.options.keyDown.isDown();
        boolean l = mc.options.keyLeft.isDown();
        boolean r = mc.options.keyRight.isDown();

        float yaw = player.getYRot();
        float pitch = player.getXRot();

        boolean walkChanged = forceWalkUpdate || f != lastF || b != lastB || l != lastL || r != lastR;
        if (walkChanged) {
            sendWalkCommand(f, b, l, r);

            lastF = f;
            lastB = b;
            lastL = l;
            lastR = r;

            if (forceWalkUpdate) {
                forceWalkUpdate = false;
            }
        }

        float yawDelta = Math.abs(yaw - lastYaw);
        float pitchDelta = Math.abs(pitch - lastPitch);

        boolean headChanged = forceHeadUpdate
                || yawDelta > YAW_SENSITIVITY_DEG
                || pitchDelta > PITCH_SENSITIVITY_DEG;

        if (headChanged) {
            sendHeadCommand(yaw, pitch);

            lastYaw = yaw;
            lastPitch = pitch;

            if (forceHeadUpdate) {
                forceHeadUpdate = false;
            }
        }
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
        } else {
            lastYaw = 0.0f;
            lastPitch = 0.0f;
        }
    }

    // ==================== RobotListener HTTP 명령 ====================

    private void sendWalkCommand(boolean f, boolean b, boolean l, boolean r) {
        String url = String.format(
                "%s/?command=set_walk&f=%d&b=%d&l=%d&r=%d",
                serverUrl,
                f ? 1 : 0,
                b ? 1 : 0,
                l ? 1 : 0,
                r ? 1 : 0
        );

        sendAsyncDirect(url).thenAccept(success -> {
            if (success) {
                walkSent++;
            } else {
                errors++;
            }
        });
    }

    private void sendHeadCommand(float yawDeg, float pitchDeg) {
        float yawRad = (float) Math.toRadians(yawDeg);
        float pitchRad = (float) Math.toRadians(-pitchDeg);

        yawRad = clamp(yawRad, NECK_MIN, NECK_MAX);
        pitchRad = clamp(pitchRad, HEAD_MIN, HEAD_MAX);

        String url = String.format(
                "%s/?command=set_head&yaw=%.3f&pitch=%.3f",
                serverUrl, yawRad, pitchRad
        );

        sendAsyncDirect(url).thenAccept(success -> {
            if (success) {
                headSent++;
            } else {
                errors++;
            }
        });
    }

    private void sendStopAll() {
        String url = String.format("%s/?command=stop_all", serverUrl);
        sendAsyncDirect(url);
    }

    // ==================== HTTP 공통 ====================

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

    // ==================== 기타 유틸/Getter ====================

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

    public boolean isConnected() {
        return connected;
    }

    public String getRobotAddress() {
        return String.format("%s:%d", serverIp, serverPort);
    }

    public void shutdown() {
        LOGGER.info("Shutting down WebotsController...");
        if (robotListenerEnabled) {
            sendStopAll();
        }
        LOGGER.info("Shutdown complete");
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (Math.min(v, max));
    }
}
