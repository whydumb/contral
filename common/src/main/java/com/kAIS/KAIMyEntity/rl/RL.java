package com.kAIS.KAIMyEntity.rl;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import com.kAIS.KAIMyEntity.urdf.URDFRobotModel;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.*;

/**
 * MuJoCo 스타일 RL 환경 핵심 구현
 * 
 * 통합 클래스:
 * - 관측 상태 (Observation)
 * - 행동 적용 (Action)
 * - 보상 계산 (Reward)
 * - 환경 설정 (Config)
 */
public class RLEnvironmentCore {
    private static final Logger logger = LogManager.getLogger();
    
    // ========== 싱글톤 ==========
    private static volatile RLEnvironmentCore instance;
    
    public static RLEnvironmentCore getInstance() {
        if (instance == null) {
            synchronized (RLEnvironmentCore.class) {
                if (instance == null) {
                    instance = new RLEnvironmentCore();
                }
            }
        }
        return instance;
    }
    
    // ========== 환경 상태 ==========
    private URDFModelOpenGLWithSTL renderer;
    private URDFRobotModel robot;
    
    private final Config config = new Config();
    private final List<JointState> jointStates = new ArrayList<>();
    private final Map<String, Integer> jointIndexMap = new HashMap<>();
    
    private int stepCount = 0;
    private float episodeReward = 0f;
    private float lastReward = 0f;
    private boolean isDone = false;
    private boolean isInitialized = false;
    
    // 이전 상태 (보상 계산용)
    private float[] prevRootPosition = new float[3];
    private float[] prevJointPositions;
    
    // ========== 초기화 ==========
    
    private RLEnvironmentCore() {
        logger.info("RLEnvironmentCore created");
    }
    
    /**
     * 렌더러 연결 및 환경 초기화
     */
    public void initialize(URDFModelOpenGLWithSTL renderer) {
        this.renderer = renderer;
        this.robot = renderer.getRobotModel();
        
        if (robot == null || robot.joints == null) {
            logger.warn("No robot model available");
            isInitialized = false;
            return;
        }
        
        // 관절 정보 수집
        jointStates.clear();
        jointIndexMap.clear();
        int idx = 0;
        
        for (var joint : robot.joints) {
            if (joint.isMovable()) {
                float lower = (joint.limit != null) ? joint.limit.lower : (float)-Math.PI;
                float upper = (joint.limit != null) ? joint.limit.upper : (float)Math.PI;
                
                JointState js = new JointState();
                js.name = joint.name;
                js.position = joint.currentPosition;
                js.velocity = 0f;
                js.torque = 0f;
                js.minLimit = lower;
                js.maxLimit = upper;
                js.targetPosition = joint.currentPosition;
                
                jointStates.add(js);
                jointIndexMap.put(joint.name, idx++);
            }
        }
        
        prevJointPositions = new float[jointStates.size()];
        
        isInitialized = true;
        logger.info("RLEnvironmentCore initialized with {} joints, obs={}, act={}",
            jointStates.size(), getObservationDim(), getActionDim());
    }
    
    // ========== 환경 인터페이스 (Gym 스타일) ==========
    
    /**
     * 환경 리셋
     * @return 초기 관측값 (flat array)
     */
    public float[] reset() {
        if (!isInitialized) {
            logger.warn("Environment not initialized");
            return new float[0];
        }
        
        stepCount = 0;
        episodeReward = 0f;
        lastReward = 0f;
        isDone = false;
        
        // 관절 초기화 (노이즈 추가 옵션)
        Random rand = config.randomizeInitial ? new Random() : null;
        
        for (int i = 0; i < jointStates.size(); i++) {
            JointState js = jointStates.get(i);
            float initPos = (js.minLimit + js.maxLimit) / 2f; // 중앙값
            
            if (rand != null) {
                float range = (js.maxLimit - js.minLimit) * config.initNoiseScale;
                initPos += (rand.nextFloat() - 0.5f) * range;
            }
            
            js.position = initPos;
            js.velocity = 0f;
            js.torque = 0f;
            js.targetPosition = initPos;
            prevJointPositions[i] = initPos;
            
            // 실제 모델에 적용
            if (renderer != null) {
                renderer.setJointTarget(js.name, initPos);
            }
        }
        
        // 루트 위치 저장
        prevRootPosition = getRootPosition();
        
        logger.debug("Environment reset, step=0");
        return getObservation();
    }
    
    /**
     * 환경 스텝 실행
     * @param action 정규화된 행동 [-1, 1] 범위
     * @return StepResult (obs, reward, done, truncated, info)
     */
    public StepResult step(float[] action) {
        StepResult result = new StepResult();
        
        if (!isInitialized || action == null) {
            result.observation = new float[0];
            result.reward = 0;
            result.done = true;
            result.truncated = false;
            return result;
        }
        
        // 1. 행동 적용
        applyAction(action);
        
        // 2. 물리 시뮬레이션 (frameSkip 반복)
        for (int i = 0; i < config.frameSkip; i++) {
            simulateStep();
        }
        
        // 3. 상태 업데이트
        updateJointStates();
        
        // 4. 관측 수집
        result.observation = getObservation();
        
        // 5. 보상 계산
        result.reward = calculateReward(action);
        lastReward = result.reward;
        episodeReward += result.reward;
        
        // 6. 종료 조건 확인
        stepCount++;
        result.done = checkTermination();
        result.truncated = stepCount >= config.maxEpisodeSteps;
        isDone = result.done || result.truncated;
        
        // 7. 정보 저장
        result.info.put("step", stepCount);
        result.info.put("episode_reward", episodeReward);
        result.info.put("is_healthy", isHealthy());
        
        // 이전 상태 업데이트
        for (int i = 0; i < jointStates.size(); i++) {
            prevJointPositions[i] = jointStates.get(i).position;
        }
        prevRootPosition = getRootPosition();
        
        return result;
    }
    
    /**
     * 행동 적용
     */
    private void applyAction(float[] action) {
        int numActions = Math.min(action.length, jointStates.size());
        
        for (int i = 0; i < numActions; i++) {
            JointState js = jointStates.get(i);
            float a = Math.max(-1f, Math.min(1f, action[i])); // 클램프
            
            switch (config.actionMode) {
                case TORQUE:
                    // 토크 직접 적용
                    js.torque = a * config.maxTorque;
                    break;
                    
                case POSITION:
                    // 목표 위치 (관절 범위 내)
                    float range = js.maxLimit - js.minLimit;
                    js.targetPosition = js.minLimit + (a + 1f) / 2f * range;
                    break;
                    
                case VELOCITY:
                    // 목표 속도
                    js.targetVelocity = a * config.maxVelocity;
                    break;
            }
            
            // 렌더러에 적용
            if (renderer != null) {
                if (config.actionMode == ActionMode.POSITION) {
                    renderer.setJointTarget(js.name, js.targetPosition);
                }
            }
        }
    }
    
    /**
     * 단일 물리 스텝 시뮬레이션
     */
    private void simulateStep() {
        // PD 제어 적용 (Position 모드)
        if (config.actionMode == ActionMode.POSITION) {
            for (JointState js : jointStates) {
                float error = js.targetPosition - js.position;
                float deriv = -js.velocity;
                js.torque = config.kp * error + config.kd * deriv;
                js.torque = Math.max(-config.maxTorque, Math.min(config.maxTorque, js.torque));
            }
        }
        
        // 간단한 물리 적분 (실제로는 ODE4J 사용)
        float dt = config.timeStep;
        for (JointState js : jointStates) {
            // 가속도 (단순화: torque = I * alpha, I=1 가정)
            float acceleration = js.torque;
            
            // 속도 업데이트
            js.velocity += acceleration * dt;
            js.velocity *= config.damping; // 감쇠
            
            // 위치 업데이트
            js.position += js.velocity * dt;
            
            // 관절 제한 적용
            if (js.position < js.minLimit) {
                js.position = js.minLimit;
                js.velocity = 0;
            } else if (js.position > js.maxLimit) {
                js.position = js.maxLimit;
                js.velocity = 0;
            }
        }
    }
    
    /**
     * 관절 상태를 실제 모델에서 업데이트
     */
    private void updateJointStates() {
        if (robot == null || robot.joints == null) return;
        
        for (var joint : robot.joints) {
            Integer idx = jointIndexMap.get(joint.name);
            if (idx != null) {
                JointState js = jointStates.get(idx);
                
                // 이전 위치로 속도 추정
                float prevPos = js.position;
                js.position = joint.currentPosition;
                js.velocity = (js.position - prevPos) / config.timeStep;
            }
        }
    }
    
    // ========== 관측 (Observation) ==========
    
    /**
     * 현재 관측값 반환
     */
    public float[] getObservation() {
        List<Float> obs = new ArrayList<>();
        
        // 1. 관절 위치 (정규화)
        for (JointState js : jointStates) {
            float normalized = normalizeJointPosition(js);
            obs.add(normalized);
        }
        
        // 2. 관절 속도 (스케일링)
        if (config.includeVelocities) {
            for (JointState js : jointStates) {
                obs.add(js.velocity / config.maxVelocity);
            }
        }
        
        // 3. 루트 바디 상태
        float[] rootPos = getRootPosition();
        obs.add(rootPos[0]); // x (이동 방향)
        obs.add(rootPos[1]); // y (높이)
        obs.add(rootPos[2]); // z
        
        float[] rootVel = getRootVelocity();
        obs.add(rootVel[0]);
        obs.add(rootVel[1]);
        obs.add(rootVel[2]);
        
        // 4. 루트 방향 (sin, cos로 표현)
        float[] rootOri = getRootOrientation();
        obs.add((float)Math.sin(rootOri[1])); // yaw sin
        obs.add((float)Math.cos(rootOri[1])); // yaw cos
        
        // 5. 목표 정보 (선택)
        if (config.targetSpeed > 0) {
            obs.add(config.targetSpeed);
            obs.add(getCurrentSpeed());
        }
        
        // float[] 변환
        float[] result = new float[obs.size()];
        for (int i = 0; i < obs.size(); i++) {
            result[i] = obs.get(i);
        }
        return result;
    }
    
    private float normalizeJointPosition(JointState js) {
        float range = js.maxLimit - js.minLimit;
        if (range <= 0) return 0;
        return 2f * (js.position - js.minLimit) / range - 1f; // [-1, 1]
    }
    
    private float[] getRootPosition() {
        // TODO: 실제 루트 바디 위치 가져오기
        return new float[]{0f, 1.0f, 0f};
    }
    
    private float[] getRootVelocity() {
        float[] current = getRootPosition();
        return new float[]{
            (current[0] - prevRootPosition[0]) / config.timeStep,
            (current[1] - prevRootPosition[1]) / config.timeStep,
            (current[2] - prevRootPosition[2]) / config.timeStep
        };
    }
    
    private float[] getRootOrientation() {
        // TODO: 실제 루트 방향 가져오기 (roll, pitch, yaw)
        return new float[]{0f, 0f, 0f};
    }
    
    private float getCurrentSpeed() {
        float[] vel = getRootVelocity();
        return (float)Math.sqrt(vel[0]*vel[0] + vel[2]*vel[2]);
    }
    
    // ========== 보상 계산 (Reward) ==========
    
    /**
     * MuJoCo 스타일 보상 계산
     */
    private float calculateReward(float[] action) {
        float reward = 0f;
        
        // 1. 살아있음 보상
        reward += config.aliveBonus;
        
        // 2. 전진 보상
        float[] rootPos = getRootPosition();
        float forwardProgress = rootPos[0] - prevRootPosition[0];
        reward += forwardProgress * config.forwardWeight;
        
        // 3. 속도 매칭 보상
        if (config.targetSpeed > 0) {
            float speedError = Math.abs(getCurrentSpeed() - config.targetSpeed);
            reward -= speedError * config.speedMatchWeight;
        }
        
        // 4. 제어 비용 (토크 사용량)
        float controlCost = 0f;
        for (float a : action) {
            controlCost += a * a;
        }
        reward -= controlCost * config.controlCostWeight;
        
        // 5. 건강 보상
        if (isHealthy()) {
            reward += config.healthyReward;
        }
        
        // 6. 관절 속도 페널티 (부드러운 동작)
        float velocityPenalty = 0f;
        for (JointState js : jointStates) {
            velocityPenalty += js.velocity * js.velocity;
        }
        reward -= velocityPenalty * config.velocityPenaltyWeight;
        
        return reward;
    }
    
    // ========== 종료 조건 ==========
    
    /**
     * 에피소드 종료 확인
     */
    private boolean checkTermination() {
        if (!config.terminateOnFall) return false;
        return !isHealthy();
    }
    
    /**
     * 건강 상태 확인 (쓰러짐 감지)
     */
    public boolean isHealthy() {
        float[] rootPos = getRootPosition();
        
        // 높이 체크
        if (rootPos[1] < config.minHeight || rootPos[1] > config.maxHeight) {
            return false;
        }
        
        // 기울기 체크
        float[] ori = getRootOrientation();
        if (Math.abs(ori[0]) > config.maxTilt || Math.abs(ori[2]) > config.maxTilt) {
            return false;
        }
        
        return true;
    }
    
    // ========== 공간 정보 ==========
    
    public int getObservationDim() {
        int dim = jointStates.size(); // 위치
        if (config.includeVelocities) dim += jointStates.size(); // 속도
        dim += 6; // 루트 위치 + 속도
        dim += 2; // 루트 방향 (sin, cos)
        if (config.targetSpeed > 0) dim += 2; // 목표/현재 속도
        return dim;
    }
    
    public int getActionDim() {
        return jointStates.size();
    }
    
    public int getJointCount() {
        return jointStates.size();
    }
    
    public List<String> getJointNames() {
        List<String> names = new ArrayList<>();
        for (JointState js : jointStates) {
            names.add(js.name);
        }
        return names;
    }
    
    // ========== 상태 조회 ==========
    
    public boolean isInitialized() { return isInitialized; }
    public boolean isDone() { return isDone; }
    public int getStepCount() { return stepCount; }
    public float getEpisodeReward() { return episodeReward; }
    public float getLastReward() { return lastReward; }
    public Config getConfig() { return config; }
    
    /**
     * 특정 관절 위치 가져오기
     */
    public float getJointPosition(String name) {
        Integer idx = jointIndexMap.get(name);
        if (idx == null) return 0;
        return jointStates.get(idx).position;
    }
    
    /**
     * 특정 관절 속도 가져오기
     */
    public float getJointVelocity(String name) {
        Integer idx = jointIndexMap.get(name);
        if (idx == null) return 0;
        return jointStates.get(idx).velocity;
    }
    
    /**
     * 외부에서 관절 위치 설정 (GUI 등)
     */
    public void setJointPosition(String name, float position) {
        Integer idx = jointIndexMap.get(name);
        if (idx == null) return;
        jointStates.get(idx).position = position;
        jointStates.get(idx).targetPosition = position;
    }
    
    /**
     * 디버그 정보
     */
    public Map<String, Object> getDebugInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("initialized", isInitialized);
        info.put("step", stepCount);
        info.put("episodeReward", String.format("%.3f", episodeReward));
        info.put("lastReward", String.format("%.4f", lastReward));
        info.put("healthy", isHealthy());
        info.put("joints", jointStates.size());
        info.put("obsDim", getObservationDim());
        info.put("actDim", getActionDim());
        info.put("actionMode", config.actionMode.name());
        return info;
    }
    
    // ========== 내부 클래스 ==========
    
    /**
     * 관절 상태
     */
    private static class JointState {
        String name;
        float position;
        float velocity;
        float torque;
        float minLimit;
        float maxLimit;
        float targetPosition;
        float targetVelocity;
    }
    
    /**
     * 스텝 결과
     */
    public static class StepResult {
        public float[] observation;
        public float reward;
        public boolean done;
        public boolean truncated;
        public Map<String, Object> info = new HashMap<>();
        
        /**
         * 바이트 배열로 직렬화 (Python 통신용)
         */
        public byte[] toBytes() {
            int obsLen = observation != null ? observation.length : 0;
            ByteBuffer buffer = ByteBuffer.allocate(obsLen * 4 + 4 + 1 + 1);
            
            for (int i = 0; i < obsLen; i++) {
                buffer.putFloat(observation[i]);
            }
            buffer.putFloat(reward);
            buffer.put((byte)(done ? 1 : 0));
            buffer.put((byte)(truncated ? 1 : 0));
            
            return buffer.array();
        }
    }
    
    /**
     * 행동 모드
     */
    public enum ActionMode {
        TORQUE,     // 직접 토크 제어
        POSITION,   // 목표 위치 (PD 제어)
        VELOCITY    // 목표 속도
    }
    
    /**
     * 환경 설정
     */
    public static class Config {
        // 시뮬레이션
        public float timeStep = 0.02f;          // 20ms
        public int frameSkip = 4;               // 행동 반복
        public int maxEpisodeSteps = 1000;
        
        // 관측
        public boolean includeVelocities = true;
        
        // 행동
        public ActionMode actionMode = ActionMode.POSITION;
        public float maxTorque = 100f;
        public float maxVelocity = 10f;
        
        // PD 제어
        public float kp = 100f;
        public float kd = 10f;
        public float damping = 0.99f;
        
        // 보상 가중치
        public float aliveBonus = 0.5f;
        public float forwardWeight = 1.0f;
        public float speedMatchWeight = 0.5f;
        public float controlCostWeight = 0.1f;
        public float healthyReward = 0.2f;
        public float velocityPenaltyWeight = 0.01f;
        
        // 목표
        public float targetSpeed = 1.0f;        // m/s
        
        // 종료 조건
        public boolean terminateOnFall = true;
        public float minHeight = 0.3f;
        public float maxHeight = 2.0f;
        public float maxTilt = 1.0f;            // ~57도
        
        // 초기화
        public boolean randomizeInitial = true;
        public float initNoiseScale = 0.1f;
    }
    
    // ========== 유틸리티 ==========
    
    /**
     * float 배열을 바이트로 변환
     */
    public static byte[] floatsToBytes(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }
    
    /**
     * 바이트를 float 배열로 변환
     */
    public static float[] bytesToFloats(byte[] bytes) {
        FloatBuffer buffer = ByteBuffer.wrap(bytes).asFloatBuffer();
        float[] floats = new float[bytes.length / 4];
        buffer.get(floats);
        return floats;
    }
}