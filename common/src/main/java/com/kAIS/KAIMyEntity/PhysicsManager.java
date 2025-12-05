package com.kAIS.KAIMyEntity;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.client.Minecraft;

public class PhysicsManager {
    public static final Logger logger = LogManager.getLogger();
    private static PhysicsManager inst;
    private static final String gameDirectory = Minecraft.getInstance().gameDirectory.getAbsolutePath();
    
    private static final String ODE4J_VERSION = "0.5.4";
    private static final String ODE4J_CORE_URL = "https://repo1.maven.org/maven2/org/ode4j/core/" + ODE4J_VERSION + "/core-" + ODE4J_VERSION + ".jar";
    private static final String ODE4J_CORE_FILE = "ode4j-core-" + ODE4J_VERSION + ".jar";
    
    private URLClassLoader ode4jClassLoader;
    private boolean initialized = false;
    
    // ODE4J 클래스들 (리플렉션용)
    private Class<?> odeHelperClass;
    private Class<?> dWorldClass;
    private Class<?> dSpaceClass;
    private Class<?> dBodyClass;
    private Class<?> dMassClass;
    private Class<?> dGeomClass;
    private Class<?> dContactClass;
    private Class<?> dContactGeomClass;
    private Class<?> dJointGroupClass;
    private Class<?> dGeomNearCallbackClass;
    private Class<?> dContactBufferClass;
    
    private Object world;
    private Object space;
    private Object contactGroup;
    
    public static PhysicsManager GetInst() {
        if (inst == null) {
            inst = new PhysicsManager();
            inst.init();
        }
        return inst;
    }
    
    private void downloadFile(String urlStr, File file) throws IOException {
        if (file.exists()) {
            logger.info("ODE4J already exists: " + file.getAbsolutePath());
            return;
        }
        
        logger.info("Downloading ODE4J from: " + urlStr);
        try {
            URL url = new URI(urlStr).toURL();
            FileUtils.copyURLToFile(url, file, 30000, 30000);
            logger.info("Download complete: " + file.getAbsolutePath());
        } catch (Exception e) {
            file.delete();
            logger.error("Failed to download ODE4J!", e);
            throw new IOException("Download failed", e);
        }
    }
    
    private void init() {
        if (initialized) return;
        
        try {
            // 1. JAR 다운로드
            File ode4jFile = new File(gameDirectory, ODE4J_CORE_FILE);
            downloadFile(ODE4J_CORE_URL, ode4jFile);
            
            // 2. 별도 ClassLoader로 로드
            URL[] urls = new URL[] { ode4jFile.toURI().toURL() };
            ode4jClassLoader = new URLClassLoader(urls, this.getClass().getClassLoader());
            
            // 3. 클래스 로드
            odeHelperClass = ode4jClassLoader.loadClass("org.ode4j.ode.OdeHelper");
            dWorldClass = ode4jClassLoader.loadClass("org.ode4j.ode.DWorld");
            dSpaceClass = ode4jClassLoader.loadClass("org.ode4j.ode.DSpace");
            dBodyClass = ode4jClassLoader.loadClass("org.ode4j.ode.DBody");
            dMassClass = ode4jClassLoader.loadClass("org.ode4j.ode.DMass");
            dGeomClass = ode4jClassLoader.loadClass("org.ode4j.ode.DGeom");
            dContactClass = ode4jClassLoader.loadClass("org.ode4j.ode.DContact");
            dContactGeomClass = ode4jClassLoader.loadClass("org.ode4j.ode.DContactGeom");
            dJointGroupClass = ode4jClassLoader.loadClass("org.ode4j.ode.DJointGroup");
            dGeomNearCallbackClass = ode4jClassLoader.loadClass("org.ode4j.ode.DGeom$DNearCallback");
            dContactBufferClass = ode4jClassLoader.loadClass("org.ode4j.ode.DContactBuffer");
            
            // 4. ODE 초기화
            Method initODE = odeHelperClass.getMethod("initODE2", int.class);
            initODE.invoke(null, 0);
            
            // 5. 월드 생성
            Method createWorld = odeHelperClass.getMethod("createWorld");
            world = createWorld.invoke(null);
            
            // 중력 설정
            Method setGravity = dWorldClass.getMethod("setGravity", double.class, double.class, double.class);
            setGravity.invoke(world, 0.0, -9.81, 0.0);
            
            // ERP, CFM 설정 (안정성)
            try {
                Method setERP = dWorldClass.getMethod("setERP", double.class);
                Method setCFM = dWorldClass.getMethod("setCFM", double.class);
                setERP.invoke(world, 0.8);
                setCFM.invoke(world, 1e-5);
            } catch (Exception e) {
                logger.debug("Could not set ERP/CFM: {}", e.getMessage());
            }
            
            // 6. 공간 생성
            Method createHashSpace = odeHelperClass.getMethod("createHashSpace", 
                ode4jClassLoader.loadClass("org.ode4j.ode.DSpace"));
            space = createHashSpace.invoke(null, (Object) null);
            
            // 7. Contact Joint Group 생성
            Method createJointGroup = odeHelperClass.getMethod("createJointGroup");
            contactGroup = createJointGroup.invoke(null);
            
            initialized = true;
            logger.info("ODE4J initialized successfully with collision support!");
            
        } catch (Exception e) {
            logger.error("Failed to initialize ODE4J!", e);
            throw new RuntimeException("ODE4J initialization failed", e);
        }
    }
    
    // ========== Body 관련 메서드 ==========
    
    public Object createBody() {
        try {
            Method createBody = odeHelperClass.getMethod("createBody", dWorldClass);
            return createBody.invoke(null, world);
        } catch (Exception e) {
            logger.error("Failed to create body", e);
            return null;
        }
    }
    
    public void setBodyPosition(Object body, double x, double y, double z) {
        try {
            Method setPosition = dBodyClass.getMethod("setPosition", double.class, double.class, double.class);
            setPosition.invoke(body, x, y, z);
        } catch (Exception e) {
            logger.error("Failed to set body position", e);
        }
    }
    
    public double[] getBodyPosition(Object body) {
        try {
            Method getPosition = dBodyClass.getMethod("getPosition");
            Object pos = getPosition.invoke(body);
            return extractVector3(pos);
        } catch (Exception e) {
            logger.error("Failed to get body position", e);
            return new double[] {0, 0, 0};
        }
    }
    
    /**
     * 강체 선형 속도 가져오기
     */
    public double[] getBodyLinearVel(Object body) {
        try {
            Method getLinearVel = dBodyClass.getMethod("getLinearVel");
            Object vel = getLinearVel.invoke(body);
            return extractVector3(vel);
        } catch (Exception e) {
            logger.error("Failed to get body linear velocity", e);
            return new double[] {0, 0, 0};
        }
    }
    
    /**
     * 강체 선형 속도 설정
     */
    public void setBodyLinearVel(Object body, double x, double y, double z) {
        try {
            Method setLinearVel = dBodyClass.getMethod("setLinearVel", 
                double.class, double.class, double.class);
            setLinearVel.invoke(body, x, y, z);
        } catch (Exception e) {
            logger.error("Failed to set body linear velocity", e);
        }
    }
    
    /**
     * 강체 각속도 가져오기
     */
    public double[] getBodyAngularVel(Object body) {
        try {
            Method getAngularVel = dBodyClass.getMethod("getAngularVel");
            Object vel = getAngularVel.invoke(body);
            return extractVector3(vel);
        } catch (Exception e) {
            logger.error("Failed to get body angular velocity", e);
            return new double[] {0, 0, 0};
        }
    }
    
    /**
     * 강체 각속도 설정
     */
    public void setBodyAngularVel(Object body, double x, double y, double z) {
        try {
            Method setAngularVel = dBodyClass.getMethod("setAngularVel", 
                double.class, double.class, double.class);
            setAngularVel.invoke(body, x, y, z);
        } catch (Exception e) {
            logger.error("Failed to set body angular velocity", e);
        }
    }
    
    /**
     * 강체 회전 가져오기 (쿼터니언)
     */
    public double[] getBodyQuaternion(Object body) {
        try {
            Method getQuaternion = dBodyClass.getMethod("getQuaternion");
            Object quat = getQuaternion.invoke(body);
            return extractVector4(quat);
        } catch (Exception e) {
            logger.error("Failed to get body quaternion", e);
            return new double[] {1, 0, 0, 0}; // identity quaternion
        }
    }
    
    /**
     * 강체 회전 설정 (쿼터니언)
     */
    public void setBodyQuaternion(Object body, double w, double x, double y, double z) {
        try {
            // DQuaternion 생성
            Class<?> dQuaternionClass = ode4jClassLoader.loadClass("org.ode4j.math.DQuaternion");
            Object quat = dQuaternionClass.getConstructor(double.class, double.class, double.class, double.class)
                .newInstance(w, x, y, z);
            
            Method setQuaternion = dBodyClass.getMethod("setQuaternion", dQuaternionClass);
            setQuaternion.invoke(body, quat);
        } catch (Exception e) {
            logger.error("Failed to set body quaternion", e);
        }
    }
    
    public void addForce(Object body, double fx, double fy, double fz) {
        try {
            Method addForce = dBodyClass.getMethod("addForce", double.class, double.class, double.class);
            addForce.invoke(body, fx, fy, fz);
        } catch (Exception e) {
            logger.error("Failed to add force", e);
        }
    }
    
    public void addTorque(Object body, double tx, double ty, double tz) {
        try {
            Method addTorque = dBodyClass.getMethod("addTorque", double.class, double.class, double.class);
            addTorque.invoke(body, tx, ty, tz);
        } catch (Exception e) {
            logger.error("Failed to add torque", e);
        }
    }
    
    /**
     * 강체 비활성화 (성능 최적화)
     */
    public void disableBody(Object body) {
        try {
            Method disable = dBodyClass.getMethod("disable");
            disable.invoke(body);
        } catch (Exception e) {
            logger.debug("Failed to disable body: {}", e.getMessage());
        }
    }
    
    /**
     * 강체 활성화
     */
    public void enableBody(Object body) {
        try {
            Method enable = dBodyClass.getMethod("enable");
            enable.invoke(body);
        } catch (Exception e) {
            logger.debug("Failed to enable body: {}", e.getMessage());
        }
    }
    
    /**
     * 강체 활성화 여부
     */
    public boolean isBodyEnabled(Object body) {
        try {
            Method isEnabled = dBodyClass.getMethod("isEnabled");
            return (boolean) isEnabled.invoke(body);
        } catch (Exception e) {
            return true;
        }
    }
    
    // ========== 월드 설정 ==========
    
    public void setGravity(double x, double y, double z) {
        try {
            Method setGravity = dWorldClass.getMethod("setGravity", double.class, double.class, double.class);
            setGravity.invoke(world, x, y, z);
        } catch (Exception e) {
            logger.error("Failed to set gravity", e);
        }
    }
    
    // ========== 물리 스텝 (수정됨) ==========
    
    public void step(double stepSize) {
        try {
            // 1. 충돌 감지 (간단한 방식 사용)
            performCollisionDetection();
            
            // 2. 월드 스텝
            Method quickStep = dWorldClass.getMethod("quickStep", double.class);
            quickStep.invoke(world, stepSize);
            
            // 3. Contact Joint Group 비우기
            Method empty = dJointGroupClass.getMethod("empty");
            empty.invoke(contactGroup);
            
        } catch (Exception e) {
            logger.error("Failed to step physics", e);
        }
    }
    
    /**
     * 충돌 감지 수행
     */
    private void performCollisionDetection() {
        try {
            // Space collide 호출
            Method spaceCollide = dSpaceClass.getMethod("collide", Object.class, dGeomNearCallbackClass);
            Object callback = createNearCallback();
            spaceCollide.invoke(space, null, callback);
        } catch (Exception e) {
            logger.debug("Collision detection skipped: {}", e.getMessage());
        }
    }
    
    /**
     * Near Callback 생성 (충돌 처리)
     */
    private Object createNearCallback() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().equals("call")) {
                    // args[0] = data (null), args[1] = geom1, args[2] = geom2
                    handleNearCallback(args[1], args[2]);
                }
                return null;
            }
        };
        
        return Proxy.newProxyInstance(
            ode4jClassLoader,
            new Class<?>[] { dGeomNearCallbackClass },
            handler
        );
    }
    
    /**
     * 충돌 처리 로직 (수정됨)
     */
    private void handleNearCallback(Object geom1, Object geom2) {
        try {
            // Body 가져오기
            Method getBody = dGeomClass.getMethod("getBody");
            Object body1 = getBody.invoke(geom1);
            Object body2 = getBody.invoke(geom2);
            
            // 둘 다 static이면 무시
            if (body1 == null && body2 == null) {
                return;
            }
            
            // DContactBuffer 생성 (최대 4개 contact)
            int maxContacts = 4;
            Object contactBuffer = dContactBufferClass.getConstructor(int.class).newInstance(maxContacts);
            
            // 충돌 검사
            Method collide = odeHelperClass.getMethod("collide", 
                dGeomClass, dGeomClass, int.class, dContactGeomClass.arrayType());
            
            // ContactGeom 배열 가져오기
            Method getGeomArray = dContactBufferClass.getMethod("getGeomBuffer");
            Object geomArray = getGeomArray.invoke(contactBuffer);
            
            int numContacts = (int) collide.invoke(null, geom1, geom2, maxContacts, geomArray);
            
            if (numContacts == 0) return;
            
            // 각 contact에 대해 joint 생성
            for (int i = 0; i < numContacts; i++) {
                Method get = dContactBufferClass.getMethod("get", int.class);
                Object contact = get.invoke(contactBuffer, i);
                
                // Surface 파라미터 설정
                Method getSurface = dContactClass.getMethod("getSurface");
                Object surface = getSurface.invoke(contact);
                
                // Surface 설정 (리플렉션)
                setSurfaceParameter(surface, "mode", 0x001 | 0x002); // dContactBounce | dContactSoftCFM
                setSurfaceParameter(surface, "mu", 0.8);
                setSurfaceParameter(surface, "bounce", 0.2);
                setSurfaceParameter(surface, "bounce_vel", 0.1);
                setSurfaceParameter(surface, "soft_cfm", 0.001);
                
                // Contact Joint 생성
                Method createContactJoint = odeHelperClass.getMethod("createContactJoint",
                    dWorldClass, dJointGroupClass, dContactClass);
                Object joint = createContactJoint.invoke(null, world, contactGroup, contact);
                
                // Joint에 body 연결
                Method attach = joint.getClass().getMethod("attach", dBodyClass, dBodyClass);
                attach.invoke(joint, body1, body2);
            }
            
        } catch (Exception e) {
            // 충돌 처리 실패는 경고만
            logger.debug("Near callback failed: {}", e.getMessage());
        }
    }
    
    /**
     * Surface 파라미터 설정 헬퍼
     */
    private void setSurfaceParameter(Object surface, String fieldName, double value) {
        try {
            var field = surface.getClass().getField(fieldName);
            if (field.getType() == int.class) {
                field.setInt(surface, (int) value);
            } else {
                field.setDouble(surface, value);
            }
        } catch (Exception e) {
            // 무시 (일부 파라미터는 없을 수 있음)
        }
    }
    
    // ========== Geometry 관련 ==========
    
    public Object createBoxGeom(double sizeX, double sizeY, double sizeZ) {
        try {
            Method createBox = odeHelperClass.getMethod("createBox", 
                dSpaceClass, double.class, double.class, double.class);
            return createBox.invoke(null, space, sizeX, sizeY, sizeZ);
        } catch (Exception e) {
            logger.error("Failed to create box geom", e);
            return null;
        }
    }
    
    /**
     * 구 지오메트리 생성
     */
    public Object createSphereGeom(double radius) {
        try {
            Method createSphere = odeHelperClass.getMethod("createSphere", 
                dSpaceClass, double.class);
            return createSphere.invoke(null, space, radius);
        } catch (Exception e) {
            logger.error("Failed to create sphere geom", e);
            return null;
        }
    }
    
    /**
     * 캡슐 지오메트리 생성
     */
    public Object createCapsuleGeom(double radius, double length) {
        try {
            Method createCapsule = odeHelperClass.getMethod("createCapsule", 
                dSpaceClass, double.class, double.class);
            return createCapsule.invoke(null, space, radius, length);
        } catch (Exception e) {
            logger.error("Failed to create capsule geom", e);
            return null;
        }
    }
    
    public void setGeomPosition(Object geom, double x, double y, double z) {
        try {
            Method setPosition = dGeomClass.getMethod("setPosition", 
                double.class, double.class, double.class);
            setPosition.invoke(geom, x, y, z);
        } catch (Exception e) {
            logger.error("Failed to set geom position", e);
        }
    }
    
    /**
     * Geometry를 Body에 연결
     */
    public void setGeomBody(Object geom, Object body) {
        try {
            Method setBody = dGeomClass.getMethod("setBody", dBodyClass);
            setBody.invoke(geom, body);
        } catch (Exception e) {
            logger.error("Failed to set geom body", e);
        }
    }
    
    public void destroyGeom(Object geom) {
        try {
            Method destroy = dGeomClass.getMethod("destroy");
            destroy.invoke(geom);
        } catch (Exception e) {
            logger.error("Failed to destroy geom", e);
        }
    }
    
    // ========== Mass 관련 ==========
    
    /**
     * Mass 객체 생성
     */
    public Object createMass() {
        try {
            Method createMass = odeHelperClass.getMethod("createMass");
            return createMass.invoke(null);
        } catch (Exception e) {
            logger.error("Failed to create mass", e);
            return null;
        }
    }
    
    /**
     * Box mass 설정
     */
    public void setMassBox(Object mass, double density, double sizeX, double sizeY, double sizeZ) {
        try {
            Method setBox = dMassClass.getMethod("setBox", 
                double.class, double.class, double.class, double.class);
            setBox.invoke(mass, density, sizeX, sizeY, sizeZ);
        } catch (Exception e) {
            logger.error("Failed to set box mass", e);
        }
    }
    
    /**
     * Sphere mass 설정
     */
    public void setMassSphere(Object mass, double density, double radius) {
        try {
            Method setSphere = dMassClass.getMethod("setSphere", double.class, double.class);
            setSphere.invoke(mass, density, radius);
        } catch (Exception e) {
            logger.error("Failed to set sphere mass", e);
        }
    }
    
    /**
     * Body에 mass 설정
     */
    public void setBodyMass(Object body, Object mass) {
        try {
            Method setMass = dBodyClass.getMethod("setMass", dMassClass);
            setMass.invoke(body, mass);
        } catch (Exception e) {
            logger.error("Failed to set body mass", e);
        }
    }
    
    // ========== 유틸리티 ==========
    
    /**
     * DVector3 추출 헬퍼
     */
    private double[] extractVector3(Object vec) {
        try {
            Method get0 = vec.getClass().getMethod("get0");
            Method get1 = vec.getClass().getMethod("get1");
            Method get2 = vec.getClass().getMethod("get2");
            
            return new double[] {
                (double) get0.invoke(vec),
                (double) get1.invoke(vec),
                (double) get2.invoke(vec)
            };
        } catch (Exception e) {
            // 대안: get(int) 메서드 시도
            try {
                Method get = vec.getClass().getMethod("get", int.class);
                return new double[] {
                    (double) get.invoke(vec, 0),
                    (double) get.invoke(vec, 1),
                    (double) get.invoke(vec, 2)
                };
            } catch (Exception e2) {
                return new double[] {0, 0, 0};
            }
        }
    }
    
    /**
     * DQuaternion 추출 헬퍼
     */
    private double[] extractVector4(Object vec) {
        try {
            Method get0 = vec.getClass().getMethod("get0");
            Method get1 = vec.getClass().getMethod("get1");
            Method get2 = vec.getClass().getMethod("get2");
            Method get3 = vec.getClass().getMethod("get3");
            
            return new double[] {
                (double) get0.invoke(vec),
                (double) get1.invoke(vec),
                (double) get2.invoke(vec),
                (double) get3.invoke(vec)
            };
        } catch (Exception e) {
            return new double[] {1, 0, 0, 0};
        }
    }
    
    // ========== 정리 ==========
    
    public void cleanup() {
        try {
            if (contactGroup != null) {
                Method destroy = dJointGroupClass.getMethod("destroy");
                destroy.invoke(contactGroup);
            }
            if (space != null) {
                Method destroy = dSpaceClass.getMethod("destroy");
                destroy.invoke(space);
            }
            if (world != null) {
                Method destroy = dWorldClass.getMethod("destroy");
                destroy.invoke(world);
            }
            Method closeODE = odeHelperClass.getMethod("closeODE");
            closeODE.invoke(null);
            
            if (ode4jClassLoader != null) {
                ode4jClassLoader.close();
            }
            
            initialized = false;
            inst = null;
            logger.info("ODE4J cleaned up.");
        } catch (Exception e) {
            logger.error("Failed to cleanup ODE4J", e);
        }
    }
    
    // ========== Getter ==========
    
    public boolean isInitialized() {
        return initialized;
    }
    
    public Object getWorld() {
        return world;
    }
    
    public Object getSpace() {
        return space;
    }
    
    public Object getContactGroup() {
        return contactGroup;
    }
    
    public URLClassLoader getClassLoader() {
        return ode4jClassLoader;
    }
    
    // 클래스 getter (HybridJointController용)
    public Class<?> getOdeHelperClass() { return odeHelperClass; }
    public Class<?> getDWorldClass() { return dWorldClass; }
    public Class<?> getDBodyClass() { return dBodyClass; }
    public Class<?> getDMassClass() { return dMassClass; }
    public Class<?> getDGeomClass() { return dGeomClass; }
}
