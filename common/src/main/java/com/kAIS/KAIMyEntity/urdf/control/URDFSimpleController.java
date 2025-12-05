package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.PhysicsManager;
import com.kAIS.KAIMyEntity.urdf.URDFJoint;
import com.kAIS.KAIMyEntity.urdf.URDFLink;
import com.kAIS.KAIMyEntity.urdf.URDFModel;
import net.minecraft.util.Mth;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.*;

/**
 * URDF 컨트롤러 - 키네마틱 모드와 ODE4J 물리 모드 통합
 * 
 * 사용법:
 * - 키네마틱만: new URDFSimpleController(allJoints)
 * - 물리 포함: new URDFSimpleController(model, allJoints, true)
 */
public final class URDFSimpleController {
    private static final Logger logger = LogManager.getLogger();

    // ========== 공통 필드 ==========
    private final Map<String, URDFJoint> joints;
    private final Map<String, Float> target = new HashMap<>();
    
    // ========== 키네마틱 모드 필드 ==========
    private float kp = 30f;
    private float kd = 6f;
    private float defaultMaxVel = 4.0f;
    private float defaultMaxAcc = 12.0f;

    // ========== 물리 모드 필드 ==========
    private final URDFModel urdfModel;
    private final PhysicsManager physics;
    private boolean usePhysics = false;
    private boolean physicsInitialized = false;

    // ODE4J 객체들 (리플렉션)
    private final Map<String, Object> bodies = new HashMap<>();
    private final Map<String, Object> masses = new HashMap<>();
    private final Map<String, Object> odeJoints = new HashMap<>();
    private final Map<String, Object> geoms = new HashMap<>();

    // 물리 제어용
    private final Map<String, Float> targetVelocities = new HashMap<>();
    private float physicsKp = 500f;
    private float physicsKd = 50f;
    private float maxTorque = 100f;
    private float maxForce = 500f;

    // 리플렉션 캐시
    private Class<?> dJointClass;
    private Class<?> dHingeJointClass;
    private Class<?> dSliderJointClass;
    private Class<?> dFixedJointClass;
    private Class<?> dBallJointClass;
    private Class<?> dGeomClass;
    private Class<?> dMassClass;
    private Class<?> dBodyClass;
    private Class<?> dWorldClass;
    private Class<?> dSpaceClass;
    private Class<?> odeHelperClass;

    // ========================================================================
    // 생성자
    // ========================================================================

    /**
     * 기존 생성자 - 순수 키네마틱 모드
     */
    public URDFSimpleController(Collection<URDFJoint> allJoints) {
        this.urdfModel = null;
        this.physics = null;
        this.usePhysics = false;
        
        Map<String, URDFJoint> m = new HashMap<>();
        for (URDFJoint j : allJoints) {
            m.put(j.name, j);
            target.put(j.name, j.currentPosition);
        }
        this.joints = m;
        
        logger.info("URDFSimpleController initialized in KINEMATIC mode with {} joints", joints.size());
    }

    /**
     * 새 생성자 - URDFModel 기반, 물리 모드 선택 가능
     * 
     * @param model URDF 모델
     * @param allJoints 모든 조인트
     * @param enablePhysics true면 ODE4J 물리 시도
     */
    public URDFSimpleController(URDFModel model, Collection<URDFJoint> allJoints, boolean enablePhysics) {
        this.urdfModel = model;
        
        // 키네마틱 기본 설정
        Map<String, URDFJoint> m = new HashMap<>();
        for (URDFJoint j : allJoints) {
            m.put(j.name, j);
            target.put(j.name, j.currentPosition);
            targetVelocities.put(j.name, 0f);
        }
        this.joints = m;

        // 물리 모드 시도
        if (enablePhysics) {
            PhysicsManager pm = null;
            try {
                pm = PhysicsManager.GetInst();
            } catch (Exception e) {
                logger.warn("PhysicsManager not available", e);
            }
            this.physics = pm;

            if (physics != null && physics.isInitialized()) {
                try {
                    initializeODE4JClasses();
                    buildPhysicsModel();
                    usePhysics = true;
                    physicsInitialized = true;
                    logger.info("URDFSimpleController initialized in PHYSICS mode with {} bodies, {} joints",
                            bodies.size(), odeJoints.size());
                } catch (Exception e) {
                    logger.error("Failed to initialize physics, falling back to kinematic", e);
                    usePhysics = false;
                    physicsInitialized = false;
                }
            } else {
                this.usePhysics = false;
                logger.info("PhysicsManager not initialized, using KINEMATIC mode");
            }
        } else {
            this.physics = null;
            this.usePhysics = false;
            logger.info("URDFSimpleController initialized in KINEMATIC mode (physics disabled)");
        }
    }

    /**
     * 편의 생성자 - 물리 모드 자동 시도
     */
    public URDFSimpleController(URDFModel model, Collection<URDFJoint> allJoints) {
        this(model, allJoints, true);
    }

    // ========================================================================
    // 공통 API
    // ========================================================================

    public void setTarget(String name, float value) {
        URDFJoint j = joints.get(name);
        if (j == null) return;

        if (j.type == URDFJoint.JointType.CONTINUOUS) {
            value = wrapToPi(value);
        }
        if (j.type == URDFJoint.JointType.REVOLUTE || j.type == URDFJoint.JointType.PRISMATIC) {
            if (j.limit != null && j.limit.hasLimits()) {
                value = Mth.clamp(value, j.limit.lower, j.limit.upper);
            }
        }
        target.put(name, value);
    }

    public void setTargets(Map<String, Float> targets) {
        for (var e : targets.entrySet()) {
            setTarget(e.getKey(), e.getValue());
        }
    }

    public float getTarget(String name) {
        return target.getOrDefault(name, 0f);
    }

    public void setGains(float kp, float kd) {
        this.kp = kp;
        this.kd = kd;
        this.physicsKp = kp * 15f;
        this.physicsKd = kd * 8f;
    }

    public void setLimits(float maxVel, float maxAcc) {
        this.defaultMaxVel = maxVel;
        this.defaultMaxAcc = maxAcc;
    }

    /**
     * 매 틱 호출; dt ≈ 1/20f (마크 20TPS 기준)
     */
    public void update(float dt) {
        if (usePhysics && physicsInitialized) {
            updatePhysics(dt);
        } else {
            updateKinematic(dt);
        }
    }

    public boolean isUsingPhysics() {
        return usePhysics && physicsInitialized;
    }

    public void cleanup() {
        bodies.clear();
        masses.clear();
        odeJoints.clear();
        geoms.clear();
        physicsInitialized = false;
        logger.info("URDFSimpleController cleaned up");
    }

    // ========================================================================
    // 키네마틱 모드
    // ========================================================================

    private void updateKinematic(float dt) {
        for (URDFJoint j : joints.values()) {
            float tgt = target.getOrDefault(j.name, j.currentPosition);
            float pos = j.currentPosition;
            float vel = j.currentVelocity;

            if (j.type == URDFJoint.JointType.CONTINUOUS) {
                float d = (float) Math.atan2(Math.sin(tgt - pos), Math.cos(tgt - pos));
                tgt = pos + d;
            }

            float err = tgt - pos;
            float acc = kp * err - kd * vel;

            float maxVel = (j.limit != null && j.limit.velocity > 0f) ? j.limit.velocity : defaultMaxVel;
            float maxAcc = defaultMaxAcc;

            acc = Mth.clamp(acc, -maxAcc, maxAcc);
            vel += acc * dt;
            vel = Mth.clamp(vel, -maxVel, maxVel);
            pos += vel * dt;

            if (j.type == URDFJoint.JointType.REVOLUTE || j.type == URDFJoint.JointType.PRISMATIC) {
                if (j.limit != null && j.limit.hasLimits()) {
                    if (pos < j.limit.lower) { pos = j.limit.lower; vel = 0f; }
                    if (pos > j.limit.upper) { pos = j.limit.upper; vel = 0f; }
                }
            } else if (j.type == URDFJoint.JointType.CONTINUOUS) {
                pos = wrapToPi(pos);
            }

            j.currentVelocity = vel;
            j.currentPosition = pos;
        }
    }

    // ========================================================================
    // 물리 모드 - 초기화
    // ========================================================================

    private void initializeODE4JClasses() throws Exception {
        ClassLoader cl = physics.getClassLoader();
        
        odeHelperClass = cl.loadClass("org.ode4j.ode.OdeHelper");
        dWorldClass = cl.loadClass("org.ode4j.ode.DWorld");
        dSpaceClass = cl.loadClass("org.ode4j.ode.DSpace");
        dBodyClass = cl.loadClass("org.ode4j.ode.DBody");
        dMassClass = cl.loadClass("org.ode4j.ode.DMass");
        dJointClass = cl.loadClass("org.ode4j.ode.DJoint");
        dHingeJointClass = cl.loadClass("org.ode4j.ode.DHingeJoint");
        dSliderJointClass = cl.loadClass("org.ode4j.ode.DSliderJoint");
        dFixedJointClass = cl.loadClass("org.ode4j.ode.DFixedJoint");
        dBallJointClass = cl.loadClass("org.ode4j.ode.DBallJoint");
        dGeomClass = cl.loadClass("org.ode4j.ode.DGeom");
        
        logger.debug("ODE4J classes loaded successfully");
    }

    private void buildPhysicsModel() throws Exception {
        if (urdfModel == null) {
            throw new IllegalStateException("URDFModel is null, cannot build physics model");
        }

        Object world = physics.getWorld();
        Object space = physics.getSpace();

        // 1. 링크 → 강체 생성
        Map<String, URDFLink> links = getLinksFromModel();
        for (URDFLink link : links.values()) {
            if (isFixedLink(link)) continue;
            
            Object body = createBodyForLink(link, world);
            if (body != null) {
                bodies.put(link.name, body);
            }
        }

        // 2. 조인트 → ODE 조인트 생성
        for (URDFJoint joint : joints.values()) {
            Object odeJoint = createODEJoint(joint, world);
            if (odeJoint != null) {
                odeJoints.put(joint.name, odeJoint);
            }
        }

        logger.info("Physics model built: {} bodies, {} ODE joints", bodies.size(), odeJoints.size());
    }

    /**
     * URDFModel에서 링크 맵 가져오기 - 실제 구조에 맞게 수정 필요
     */
    private Map<String, URDFLink> getLinksFromModel() {
        // TODO: URDFModel의 실제 메서드에 맞게 수정
        // 예: return urdfModel.getLinks();
        // 또는: return urdfModel.linkMap;
        
        // 임시로 빈 맵 반환 - 실제 구조 확인 후 수정
        Map<String, URDFLink> result = new HashMap<>();
        
        // 리플렉션으로 시도
        try {
            // getLinks() 메서드 시도
            Method getLinks = urdfModel.getClass().getMethod("getLinks");
            @SuppressWarnings("unchecked")
            Map<String, URDFLink> links = (Map<String, URDFLink>) getLinks.invoke(urdfModel);
            return links;
        } catch (NoSuchMethodException e1) {
            try {
                // links 필드 직접 접근 시도
                var field = urdfModel.getClass().getField("links");
                @SuppressWarnings("unchecked")
                Map<String, URDFLink> links = (Map<String, URDFLink>) field.get(urdfModel);
                return links;
            } catch (Exception e2) {
                logger.warn("Cannot access links from URDFModel: {}", e2.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Error getting links: {}", e.getMessage());
        }
        
        return result;
    }

    private Object createBodyForLink(URDFLink link, Object world) {
        try {
            Object body = physics.createBody();
            if (body == null) return null;

            // 기본 질량 설정
            setDefaultMass(body);

            // 초기 위치 설정
            // TODO: link의 실제 origin 구조에 맞게 수정
            // physics.setBodyPosition(body, x, y, z);

            return body;
        } catch (Exception e) {
            logger.error("Failed to create body for link: {}", link.name, e);
            return null;
        }
    }

    private void setDefaultMass(Object body) {
        try {
            Method createMass = odeHelperClass.getMethod("createMass");
            Object mass = createMass.invoke(null);

            Method setBox = dMassClass.getMethod("setBox", double.class, double.class, double.class, double.class);
            setBox.invoke(mass, 1.0, 0.1, 0.1, 0.1);

            Method setMass = dBodyClass.getMethod("setMass", dMassClass);
            setMass.invoke(body, mass);
        } catch (Exception e) {
            logger.warn("Failed to set default mass", e);
        }
    }

    private Object createODEJoint(URDFJoint joint, Object world) {
        try {
            // 부모/자식 바디 찾기 - 실제 URDFJoint 구조에 맞게 수정 필요
            String parentName = getJointParentName(joint);
            String childName = getJointChildName(joint);
            
            Object parentBody = bodies.get(parentName);
            Object childBody = bodies.get(childName);

            Object odeJoint = null;

            switch (joint.type) {
                case REVOLUTE:
                case CONTINUOUS:
                    odeJoint = createHingeJoint(joint, world, parentBody, childBody);
                    break;
                case PRISMATIC:
                    odeJoint = createSliderJoint(joint, world, parentBody, childBody);
                    break;
                case FIXED:
                    odeJoint = createFixedJoint(joint, world, parentBody, childBody);
                    break;
                default:
                    logger.warn("Unsupported joint type: {} for {}", joint.type, joint.name);
                    break;
            }

            return odeJoint;
        } catch (Exception e) {
            logger.error("Failed to create ODE joint: {}", joint.name, e);
            return null;
        }
    }

    /**
     * URDFJoint에서 부모 이름 가져오기 - 실제 구조에 맞게 수정 필요
     */
    private String getJointParentName(URDFJoint joint) {
        // TODO: 실제 URDFJoint 구조에 맞게 수정
        // 예: return joint.parent;
        // 또는: return joint.parentLink;
        // 또는: return joint.getParent();
        
        try {
            var field = joint.getClass().getField("parent");
            return (String) field.get(joint);
        } catch (Exception e1) {
            try {
                var field = joint.getClass().getField("parentLink");
                Object parent = field.get(joint);
                if (parent instanceof String) return (String) parent;
                if (parent instanceof URDFLink) return ((URDFLink) parent).name;
            } catch (Exception e2) {
                // ignore
            }
        }
        return "";
    }

    /**
     * URDFJoint에서 자식 이름 가져오기 - 실제 구조에 맞게 수정 필요
     */
    private String getJointChildName(URDFJoint joint) {
        try {
            var field = joint.getClass().getField("child");
            return (String) field.get(joint);
        } catch (Exception e1) {
            try {
                var field = joint.getClass().getField("childLink");
                Object child = field.get(joint);
                if (child instanceof String) return (String) child;
                if (child instanceof URDFLink) return ((URDFLink) child).name;
            } catch (Exception e2) {
                // ignore
            }
        }
        return "";
    }

    /**
     * 조인트 축 가져오기 - 실제 구조에 맞게 수정 필요
     */
    private double[] getJointAxis(URDFJoint joint) {
        // TODO: 실제 URDFJoint.axis 구조에 맞게 수정
        // joint.axis가 Vector3f인 경우:
        // return new double[] { joint.axis.x, joint.axis.y, joint.axis.z };
        // joint.axis.xyz가 float[]인 경우:
        // return new double[] { joint.axis.xyz[0], joint.axis.xyz[1], joint.axis.xyz[2] };
        
        try {
            if (joint.axis != null) {
                var xyzField = joint.axis.getClass().getField("xyz");
                Object xyz = xyzField.get(joint.axis);
                
                if (xyz instanceof float[]) {
                    float[] arr = (float[]) xyz;
                    return new double[] { arr[0], arr[1], arr[2] };
                } else {
                    // Vector3f 등
                    Method getX = xyz.getClass().getMethod("x");
                    Method getY = xyz.getClass().getMethod("y");
                    Method getZ = xyz.getClass().getMethod("z");
                    return new double[] {
                        ((Number) getX.invoke(xyz)).doubleValue(),
                        ((Number) getY.invoke(xyz)).doubleValue(),
                        ((Number) getZ.invoke(xyz)).doubleValue()
                    };
                }
            }
        } catch (Exception e) {
            // 기본 축
        }
        return new double[] { 0, 0, 1 };
    }

    private Object createHingeJoint(URDFJoint joint, Object world, 
                                     Object parentBody, Object childBody) throws Exception {
        Method createHinge = odeHelperClass.getMethod("createHingeJoint", dWorldClass);
        Object odeJoint = createHinge.invoke(null, world);

        Method attach = dHingeJointClass.getMethod("attach", dBodyClass, dBodyClass);
        attach.invoke(odeJoint, childBody, parentBody);

        double[] axis = getJointAxis(joint);
        Method setAxis = dHingeJointClass.getMethod("setAxis", double.class, double.class, double.class);
        setAxis.invoke(odeJoint, axis[0], axis[1], axis[2]);

        // 리밋 설정 (REVOLUTE만)
        if (joint.type == URDFJoint.JointType.REVOLUTE && 
            joint.limit != null && joint.limit.hasLimits()) {
            try {
                Method setLoStop = dHingeJointClass.getMethod("setParamLoStop", double.class);
                Method setHiStop = dHingeJointClass.getMethod("setParamHiStop", double.class);
                setLoStop.invoke(odeJoint, (double) joint.limit.lower);
                setHiStop.invoke(odeJoint, (double) joint.limit.upper);
            } catch (Exception e) {
                logger.warn("Failed to set joint limits for {}", joint.name);
            }
        }

        return odeJoint;
    }

    private Object createSliderJoint(URDFJoint joint, Object world,
                                      Object parentBody, Object childBody) throws Exception {
        Method createSlider = odeHelperClass.getMethod("createSliderJoint", dWorldClass);
        Object odeJoint = createSlider.invoke(null, world);

        Method attach = dSliderJointClass.getMethod("attach", dBodyClass, dBodyClass);
        attach.invoke(odeJoint, childBody, parentBody);

        double[] axis = getJointAxis(joint);
        Method setAxis = dSliderJointClass.getMethod("setAxis", double.class, double.class, double.class);
        setAxis.invoke(odeJoint, axis[0], axis[1], axis[2]);

        return odeJoint;
    }

    private Object createFixedJoint(URDFJoint joint, Object world,
                                     Object parentBody, Object childBody) throws Exception {
        Method createFixed = odeHelperClass.getMethod("createFixedJoint", dWorldClass);
        Object odeJoint = createFixed.invoke(null, world);

        Method attach = dFixedJointClass.getMethod("attach", dBodyClass, dBodyClass);
        attach.invoke(odeJoint, childBody, parentBody);

        Method setFixed = dFixedJointClass.getMethod("setFixed");
        setFixed.invoke(odeJoint);

        return odeJoint;
    }

    // ========================================================================
    // 물리 모드 - 업데이트
    // ========================================================================

    private void updatePhysics(float dt) {
        try {
            // 1. 각 조인트에 PD 토크/힘 적용
            for (Map.Entry<String, Object> entry : odeJoints.entrySet()) {
                String jointName = entry.getKey();
                Object odeJoint = entry.getValue();
                URDFJoint urdfJoint = joints.get(jointName);

                if (urdfJoint == null) continue;

                float targetPos = target.getOrDefault(jointName, 0f);
                float targetVel = targetVelocities.getOrDefault(jointName, 0f);

                applyJointControl(odeJoint, urdfJoint, targetPos, targetVel);
            }

            // 2. 물리 스텝
            physics.step(dt);

            // 3. ODE → URDF 동기화
            syncJointStates();

        } catch (Exception e) {
            logger.error("Physics update failed, switching to kinematic", e);
            usePhysics = false;
        }
    }

    private void applyJointControl(Object odeJoint, URDFJoint urdfJoint,
                                    float targetPos, float targetVel) {
        try {
            if (urdfJoint.type == URDFJoint.JointType.REVOLUTE ||
                urdfJoint.type == URDFJoint.JointType.CONTINUOUS) {

                Method getAngle = dHingeJointClass.getMethod("getAngle");
                Method getAngleRate = dHingeJointClass.getMethod("getAngleRate");

                float currentPos = ((Number) getAngle.invoke(odeJoint)).floatValue();
                float currentVel = ((Number) getAngleRate.invoke(odeJoint)).floatValue();

                float posError = targetPos - currentPos;
                if (urdfJoint.type == URDFJoint.JointType.CONTINUOUS) {
                    posError = wrapToPi(posError);
                }

                float velError = targetVel - currentVel;
                float torque = physicsKp * posError + physicsKd * velError;

                float limit = (urdfJoint.limit != null && urdfJoint.limit.effort > 0)
                        ? urdfJoint.limit.effort : maxTorque;
                torque = Mth.clamp(torque, -limit, limit);

                Method addTorque = dHingeJointClass.getMethod("addTorque", double.class);
                addTorque.invoke(odeJoint, (double) torque);

            } else if (urdfJoint.type == URDFJoint.JointType.PRISMATIC) {

                Method getPosition = dSliderJointClass.getMethod("getPosition");
                Method getPositionRate = dSliderJointClass.getMethod("getPositionRate");

                float currentPos = ((Number) getPosition.invoke(odeJoint)).floatValue();
                float currentVel = ((Number) getPositionRate.invoke(odeJoint)).floatValue();

                float posError = targetPos - currentPos;
                float velError = targetVel - currentVel;
                float force = physicsKp * posError + physicsKd * velError;

                float limit = (urdfJoint.limit != null && urdfJoint.limit.effort > 0)
                        ? urdfJoint.limit.effort : maxForce;
                force = Mth.clamp(force, -limit, limit);

                Method addForce = dSliderJointClass.getMethod("addForce", double.class);
                addForce.invoke(odeJoint, (double) force);
            }
        } catch (Exception e) {
            logger.warn("Failed to apply control to joint: {}", urdfJoint.name);
        }
    }

    private void syncJointStates() {
        try {
            for (Map.Entry<String, Object> entry : odeJoints.entrySet()) {
                String jointName = entry.getKey();
                Object odeJoint = entry.getValue();
                URDFJoint urdfJoint = joints.get(jointName);

                if (urdfJoint == null) continue;

                if (urdfJoint.type == URDFJoint.JointType.REVOLUTE ||
                    urdfJoint.type == URDFJoint.JointType.CONTINUOUS) {

                    Method getAngle = dHingeJointClass.getMethod("getAngle");
                    Method getAngleRate = dHingeJointClass.getMethod("getAngleRate");

                    urdfJoint.currentPosition = ((Number) getAngle.invoke(odeJoint)).floatValue();
                    urdfJoint.currentVelocity = ((Number) getAngleRate.invoke(odeJoint)).floatValue();

                } else if (urdfJoint.type == URDFJoint.JointType.PRISMATIC) {

                    Method getPosition = dSliderJointClass.getMethod("getPosition");
                    Method getPositionRate = dSliderJointClass.getMethod("getPositionRate");

                    urdfJoint.currentPosition = ((Number) getPosition.invoke(odeJoint)).floatValue();
                    urdfJoint.currentVelocity = ((Number) getPositionRate.invoke(odeJoint)).floatValue();
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to sync joint states", e);
        }
    }

    // ========================================================================
    // 물리 모드 전용 API
    // ========================================================================

    public void setTargetVelocity(String jointName, float velocity) {
        targetVelocities.put(jointName, velocity);
    }

    public void applyExternalForce(String linkName, float fx, float fy, float fz) {
        if (!usePhysics) return;
        Object body = bodies.get(linkName);
        if (body != null && physics != null) {
            physics.addForce(body, fx, fy, fz);
        }
    }

    public void applyExternalTorque(String linkName, float tx, float ty, float tz) {
        if (!usePhysics) return;
        Object body = bodies.get(linkName);
        if (body != null && physics != null) {
            physics.addTorque(body, tx, ty, tz);
        }
    }

    public float[] getLinkWorldPosition(String linkName) {
        if (!usePhysics) return new float[] { 0, 0, 0 };
        Object body = bodies.get(linkName);
        if (body != null && physics != null) {
            double[] pos = physics.getBodyPosition(body);
            return new float[] { (float) pos[0], (float) pos[1], (float) pos[2] };
        }
        return new float[] { 0, 0, 0 };
    }

    public void setGravity(float x, float y, float z) {
        if (physics != null) {
            physics.setGravity(x, y, z);
        }
    }

    public void setPhysicsGains(float kp, float kd) {
        this.physicsKp = kp;
        this.physicsKd = kd;
    }

    public void setEffortLimits(float maxTorque, float maxForce) {
        this.maxTorque = maxTorque;
        this.maxForce = maxForce;
    }

    // ========================================================================
    // 유틸리티
    // ========================================================================

    private boolean isFixedLink(URDFLink link) {
        if (link.name.equals("world")) return true;
        
        // 루트 링크 체크 - 실제 구조에 맞게 수정 필요
        if (urdfModel != null) {
            try {
                Method getRootLink = urdfModel.getClass().getMethod("getRootLink");
                Object root = getRootLink.invoke(urdfModel);
                if (root instanceof URDFLink && link.name.equals(((URDFLink) root).name)) {
                    return true;
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return false;
    }

    private static float wrapToPi(float a) {
        float twoPi = (float) (Math.PI * 2.0);
        a = a % twoPi;
        if (a > Math.PI) a -= twoPi;
        if (a < -Math.PI) a += twoPi;
        return a;
    }
}