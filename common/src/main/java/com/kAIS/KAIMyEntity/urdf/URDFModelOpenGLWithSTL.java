package com.kAIS.KAIMyEntity.urdf;

import com.kAIS.KAIMyEntity.renderer.IMMDModel;
import com.kAIS.KAIMyEntity.urdf.control.URDFSimpleController;  // ★ 추가
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.util.*;

/**
 * URDF 모델 렌더링 (STL 메시 포함) + ODE4J 물리 통합
 * ✅ 좌표계 변환 및 위치 보정 수정
 * ★ ODE4J 물리 엔진 통합 완료
 */
public class URDFModelOpenGLWithSTL implements IMMDModel {
    private static final Logger logger = LogManager.getLogger();
    private static int renderCount = 0;

    private URDFModel robotModel;
    private String modelDir;

    // ★ ODE4J 컨트롤러 추가
    private final URDFSimpleController controller;

    private final Map<String, STLLoader.STLMesh> meshCache = new HashMap<>();

    // ✅ 스케일 및 위치 설정
    private static final float GLOBAL_SCALE = 5.0f;
    private static final float BASE_HEIGHT = 1.5f;
    private float groundOffset = 0.0f;
    
    private static final boolean FLIP_NORMALS = true;
    private static final boolean DEBUG_MODE = false;

    // ✅ ROS 좌표계: z-up, x-forward
    // ✅ MC 좌표계: y-up, z-backward
    private static final Vector3f SRC_UP  = new Vector3f(0, 0, 1);
    private static final Vector3f SRC_FWD = new Vector3f(1, 0, 0);
    private static final Vector3f DST_UP  = new Vector3f(0, 1, 0);
    private static final Vector3f DST_FWD = new Vector3f(0, 0, -1);
    private static final Quaternionf Q_ROS2MC = makeUprightQuat(SRC_UP, SRC_FWD, DST_UP, DST_FWD);

    // ✅ 관절 이름 매핑 (VMD 이름 → URDF 이름)
    private final Map<String, String> jointNameMapping = new HashMap<>();
    private boolean jointMappingInitialized = false;

    public URDFModelOpenGLWithSTL(URDFModel robotModel, String modelDir) {
        this.robotModel = robotModel;
        this.modelDir = modelDir;

        // ★ URDFSimpleController 생성 (physics=true로 ODE4J 활성화 시도)
        this.controller = new URDFSimpleController(robotModel, robotModel.joints, true);
        logger.info("=== URDFSimpleController created (physics mode: {}) ===", 
                    controller.isUsingPhysics());

        logger.info("=== URDF renderer Created (Scale: {}) ===", GLOBAL_SCALE);
        loadAllMeshes();
        initJointNameMapping();
        calculateGroundOffset();
    }

    /**
     * ✅ 발-지면 오프셋 자동 계산
     */
    private void calculateGroundOffset() {
        float minZ = 0.0f;
        boolean foundFootLink = false;
        
        for (URDFLink link : robotModel.links) {
            String lowerName = link.name.toLowerCase();
            if (lowerName.contains("foot") || lowerName.contains("ankle") || 
                lowerName.contains("toe") || lowerName.contains("sole")) {
                
                foundFootLink = true;
                
                if (link.visual != null && link.visual.origin != null) {
                    minZ = Math.min(minZ, link.visual.origin.xyz.z);
                }
                
                STLLoader.STLMesh mesh = meshCache.get(link.name);
                if (mesh != null) {
                    for (STLLoader.Triangle tri : mesh.triangles) {
                        for (Vector3f v : tri.vertices) {
                            minZ = Math.min(minZ, v.z);
                        }
                    }
                }
            }
        }
        
        if (!foundFootLink) {
            logger.warn("No foot/ankle links found, calculating from all meshes");
            for (STLLoader.STLMesh mesh : meshCache.values()) {
                for (STLLoader.Triangle tri : mesh.triangles) {
                    for (Vector3f v : tri.vertices) {
                        minZ = Math.min(minZ, v.z);
                    }
                }
            }
        }
        
        groundOffset = -minZ * GLOBAL_SCALE;
        logger.info("✓ Ground offset calculated: {:.3f} blocks (raw Z: {:.3f}m, found feet: {})", 
                    groundOffset, minZ, foundFootLink);
    }

    /**
     * ✅ 관절 이름 매핑 초기화
     */
    private void initJointNameMapping() {
        logger.info("=== Initializing Joint Name Mapping ===");
        
        Set<String> urdfJointNames = new HashSet<>();
        for (URDFJoint j : robotModel.joints) {
            urdfJointNames.add(j.name);
            urdfJointNames.add(j.name.toLowerCase());
        }
        
        Map<String, String[]> vmdToUrdfCandidates = new HashMap<>();
        vmdToUrdfCandidates.put("head_pan", new String[]{"head_pan", "HeadYaw", "head_yaw", "Neck", "neck"});
        vmdToUrdfCandidates.put("head_tilt", new String[]{"head_tilt", "HeadPitch", "head_pitch", "Head", "head"});
        vmdToUrdfCandidates.put("l_sho_pitch", new String[]{"l_sho_pitch", "LShoulderPitch", "l_shoulder_pitch", "LeftShoulderPitch", "left_shoulder_pitch"});
        vmdToUrdfCandidates.put("l_sho_roll", new String[]{"l_sho_roll", "LShoulderRoll", "l_shoulder_roll", "LeftShoulderRoll", "left_shoulder_roll"});
        vmdToUrdfCandidates.put("l_el", new String[]{"l_el", "LElbowYaw", "l_elbow", "LeftElbow", "left_elbow", "LElbowRoll"});
        vmdToUrdfCandidates.put("r_sho_pitch", new String[]{"r_sho_pitch", "RShoulderPitch", "r_shoulder_pitch", "RightShoulderPitch", "right_shoulder_pitch"});
        vmdToUrdfCandidates.put("r_sho_roll", new String[]{"r_sho_roll", "RShoulderRoll", "r_shoulder_roll", "RightShoulderRoll", "right_shoulder_roll"});
        vmdToUrdfCandidates.put("r_el", new String[]{"r_el", "RElbowYaw", "r_elbow", "RightElbow", "right_elbow", "RElbowRoll"});
        vmdToUrdfCandidates.put("l_hip_pitch", new String[]{"l_hip_pitch", "LHipPitch", "LeftHipPitch"});
        vmdToUrdfCandidates.put("r_hip_pitch", new String[]{"r_hip_pitch", "RHipPitch", "RightHipPitch"});
        
        for (Map.Entry<String, String[]> entry : vmdToUrdfCandidates.entrySet()) {
            String vmdName = entry.getKey();
            for (String candidate : entry.getValue()) {
                for (URDFJoint j : robotModel.joints) {
                    if (j.name.equals(candidate) || j.name.equalsIgnoreCase(candidate)) {
                        jointNameMapping.put(vmdName, j.name);
                        logger.info("  Mapped: '{}' -> '{}'", vmdName, j.name);
                        break;
                    }
                }
                if (jointNameMapping.containsKey(vmdName)) break;
            }
        }
        
        jointMappingInitialized = true;
        logger.info("=== Joint Mapping Complete: {} mappings ===", jointNameMapping.size());
    }

    private void loadAllMeshes() {
        logger.info("=== Loading STL meshes ===");
        int loadedCount = 0;
        for (URDFLink link : robotModel.links) {
            if (link.visual != null && link.visual.geometry != null) {
                URDFLink.Geometry g = link.visual.geometry;
                if (g.type == URDFLink.Geometry.GeometryType.MESH && g.meshFilename != null) {
                    File f = new File(g.meshFilename);
                    if (f.exists()) {
                        STLLoader.STLMesh mesh = STLLoader.load(g.meshFilename);
                        if (mesh != null) {
                            if (g.scale != null && (g.scale.x != 1f || g.scale.y != 1f || g.scale.z != 1f)) {
                                STLLoader.scaleMesh(mesh, g.scale);
                            }
                            meshCache.put(link.name, mesh);
                            loadedCount++;
                            logger.info("  ✓ Loaded mesh for '{}': {} tris", link.name, mesh.getTriangleCount());
                        }
                    }
                }
            }
        }
        logger.info("=== STL Loading Complete: {}/{} meshes ===", loadedCount, robotModel.getLinkCount());
    }

    // ★ 물리 step의 단일 진입점
    /**
     * 매 틱 호출: 물리/애니메이션 업데이트
     *  - PHYSICS 모드: URDFSimpleController.update(dt) → PhysicsManager.step(dt)
     *  - KINEMATIC 모드: 키네마틱 업데이트만 수행
     */
    public void tickUpdate(float dt) {
        if (controller != null) {
            controller.update(dt);
        }
    }

    // ★ 조인트 이름 해석 헬퍼 (매핑 + 대소문자 무시)
    /**
     * VMD 이름 매핑/대소문자 무시까지 포함해서 URDFJoint를 찾아줌
     */
    private URDFJoint resolveJoint(String name) {
        if (name == null) return null;

        // 1. 매핑 적용
        String mappedName = jointNameMapping.getOrDefault(name, name);

        URDFJoint j = getJointByName(mappedName);
        if (j != null) return j;

        // 2. 매핑된 이름 안 맞으면 원본 이름도 시도
        if (!mappedName.equals(name)) {
            j = getJointByName(name);
            if (j != null) return j;
        }

        // 3. 대소문자 무시
        return getJointByNameIgnoreCase(name);
    }

    // ★ RL/컨트롤에서 쓰는 진짜 관절 타겟 설정
    /**
     * RL/컨트롤에서 쓰는 "진짜" 관절 타겟 설정
     *  - PHYSICS 모드: URDFSimpleController.setTarget()으로 넘김
     *  - KINEMATIC 모드: URDFJoint.currentPosition을 직접 갱신
     */
    public void setJointTarget(String name, float value) {
        URDFJoint j = resolveJoint(name);
        if (j == null) {
            if (DEBUG_MODE && renderCount < 5) {
                logger.warn("✗ Joint NOT FOUND in setJointTarget: '{}'", name);
            }
            return;
        }

        if (controller != null && controller.isUsingPhysics()) {
            // 물리 모드 → URDFSimpleController에 목표 각도 전달
            controller.setTarget(j.name, value);
        } else {
            // 키네마틱 모드 → 바로 URDFJoint에 반영
            j.currentPosition = value;
        }
    }

    /**
     * ✅ 여러 관절 한번에 설정
     */
    public void setJointTargets(Map<String, Float> values) {
        for (Map.Entry<String, Float> entry : values.entrySet()) {
            setJointTarget(entry.getKey(), entry.getValue());
        }
    }

    // ★ 속도 제어용 API (VELOCITY 액션 모드용)
    /**
     * 관절 목표 속도 설정 (VELOCITY 액션 모드용)
     */
    public void setJointVelocity(String name, float velocity) {
        URDFJoint j = resolveJoint(name);
        if (j == null) return;

        if (controller != null && controller.isUsingPhysics()) {
            controller.setTargetVelocity(j.name, velocity);
        } else {
            // 키네마틱 모드일 때는 미리보기용으로만
            j.currentVelocity = velocity;
        }
    }

    /**
     * ✅ 즉시 반영(프리뷰용) - 툴에서만 사용
     */
    public void setJointPreview(String name, float value) {
        String mappedName = jointNameMapping.getOrDefault(name, name);
        URDFJoint j = getJointByName(mappedName);
        
        if (j == null && !mappedName.equals(name)) {
            j = getJointByName(name);
        }
        if (j == null) {
            j = getJointByNameIgnoreCase(name);
        }
        
        if (j != null) {
            j.currentPosition = value;
            if (DEBUG_MODE && renderCount < 5) {
                logger.info("✓ Joint '{}' -> '{}' = {} rad ({} deg)", 
                    name, j.name, value, Math.toDegrees(value));
            }
        } else {
            if (DEBUG_MODE && renderCount < 5) {
                logger.warn("✗ Joint NOT FOUND: '{}' (mapped: '{}')", name, mappedName);
            }
        }
    }

    // ★ RL용 정보 API들
    /**
     * 현재 관절 속도 반환 (URDFJoint.currentVelocity 기반)
     */
    public float getJointVelocity(String jointName) {
        if (robotModel != null && robotModel.joints != null) {
            for (URDFJoint joint : robotModel.joints) {
                if (joint.name.equals(jointName)) {
                    return joint.currentVelocity;
                }
            }
        }
        return 0f;
    }

    /**
     * 루트 링크(world 기준) 위치 반환
     *  - PHYSICS 모드: URDFSimpleController + PhysicsManager에서 실제 ODE 바디 위치
     *  - Fallback: 렌더러의 BASE_HEIGHT + groundOffset 기준 가짜 값
     */
    public float[] getRootWorldPosition() {
        if (controller != null && controller.isUsingPhysics()
                && robotModel != null && robotModel.rootLinkName != null) {
            return controller.getLinkWorldPosition(robotModel.rootLinkName);
        }

        // Fallback: 대충 렌더러 기준 높이
        return new float[]{0.0f, BASE_HEIGHT + groundOffset, 0.0f};
    }

    /**
     * 물리 모드 여부 반환
     */
    public boolean isUsingPhysics() {
        return controller != null && controller.isUsingPhysics();
    }

    /**
     * 컨트롤러 직접 접근 (필요시)
     */
    public URDFSimpleController getController() {
        return controller;
    }

    // ===== 기존 API 유지 =====
    /**
     * ✅ 이동 가능한 관절 이름 목록 반환
     */
    public List<String> getMovableJointNames() {
        List<String> names = new ArrayList<>();
        if (robotModel != null && robotModel.joints != null) {
            for (URDFJoint joint : robotModel.joints) {
                if (joint.isMovable()) {
                    names.add(joint.name);
                }
            }
        }
        return names;
    }

    /**
     * ✅ 관절 제한값 반환 [lower, upper]
     */
    public float[] getJointLimits(String jointName) {
        if (robotModel != null && robotModel.joints != null) {
            for (URDFJoint joint : robotModel.joints) {
                if (joint.name.equals(jointName) && joint.limit != null) {
                    return new float[]{joint.limit.lower, joint.limit.upper};
                }
            }
        }
        return new float[]{(float)-Math.PI, (float)Math.PI};
    }

    /**
     * ✅ 현재 관절 위치 반환
     */
    public float getJointPosition(String jointName) {
        if (robotModel != null && robotModel.joints != null) {
            for (URDFJoint joint : robotModel.joints) {
                if (joint.name.equals(jointName)) {
                    return joint.currentPosition;
                }
            }
        }
        return 0f;
    }

    /**
     * ✅ 모든 관절의 현재 위치를 Map으로 반환
     */
    public Map<String, Float> getAllJointPositions() {
        Map<String, Float> positions = new HashMap<>();
        if (robotModel != null && robotModel.joints != null) {
            for (URDFJoint joint : robotModel.joints) {
                if (joint.isMovable()) {
                    positions.put(joint.name, joint.currentPosition);
                }
            }
        }
        return positions;
    }

    /**
     * ✅ 모든 관절 목록 출력 (디버깅용)
     */
    public void printAllJoints() {
        logger.info("=== URDF Joints ({}) ===", robotModel.joints.size());
        for (URDFJoint j : robotModel.joints) {
            logger.info("  - '{}' (type: {}, movable: {}, current: {})", 
                j.name, j.type, j.isMovable(), j.currentPosition);
        }
        logger.info("=== Joint Name Mappings ({}) ===", jointNameMapping.size());
        for (Map.Entry<String, String> entry : jointNameMapping.entrySet()) {
            logger.info("  - '{}' -> '{}'", entry.getKey(), entry.getValue());
        }
    }

    /**
     * ✅ 관절 이름 Set 반환 (VMDParser용)
     */
    public Set<String> getJointNames() {
        Set<String> names = new HashSet<>();
        for (URDFJoint j : robotModel.joints) {
            names.add(j.name);
        }
        return names;
    }

    // ===== 렌더링 =====
    @Override
    public void Render(Entity entityIn, float entityYaw, float entityPitch,
                       Vector3f entityTrans, float tickDelta, PoseStack poseStack, int packedLight) {

        renderCount++;
        if (renderCount % 120 == 1) {
            logger.info("=== URDF RENDER #{} (Scale: {}, Offset: {}, Physics: {}) ===", 
                       renderCount, GLOBAL_SCALE, groundOffset, isUsingPhysics());
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();

        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.solid());

        if (robotModel.rootLinkName != null) {
            poseStack.pushPose();

            float totalYOffset = BASE_HEIGHT + groundOffset;
            poseStack.translate(0.0f, totalYOffset, 0.0f);
            poseStack.mulPose(new Quaternionf(Q_ROS2MC));
            poseStack.scale(GLOBAL_SCALE, GLOBAL_SCALE, GLOBAL_SCALE);

            renderLinkRecursive(robotModel.rootLinkName, poseStack, vc, packedLight);
            poseStack.popPose();
        }

        bufferSource.endBatch(RenderType.solid());
        RenderSystem.enableCull();
    }

    private void renderLinkRecursive(String linkName, PoseStack poseStack, VertexConsumer vc, int packedLight) {
        URDFLink link = robotModel.getLink(linkName);
        if (link == null) return;

        poseStack.pushPose();

        if (link.visual != null) {
            renderVisual(link, poseStack, vc, packedLight);
        }

        for (URDFJoint childJoint : robotModel.getChildJoints(linkName)) {
            poseStack.pushPose();
            applyJointTransform(childJoint, poseStack);
            renderLinkRecursive(childJoint.childLinkName, poseStack, vc, packedLight);
            poseStack.popPose();
        }

        poseStack.popPose();
    }

    private void renderVisual(URDFLink link, PoseStack poseStack, VertexConsumer vc, int packedLight) {
        if (link.visual == null || link.visual.geometry == null) return;

        poseStack.pushPose();

        if (link.visual.origin != null) {
            applyLinkOriginTransform(link.visual.origin, poseStack);
        }

        STLLoader.STLMesh mesh = meshCache.get(link.name);
        if (mesh != null) {
            renderMesh(mesh, link, poseStack, vc, packedLight);
        }
        poseStack.popPose();
    }

    private void renderMesh(STLLoader.STLMesh mesh, URDFLink link, PoseStack poseStack,
                            VertexConsumer vc, int packedLight) {
        Matrix4f matrix = poseStack.last().pose();

        int r = 220, g = 220, b = 220, a = 255;
        if (link.visual.material != null && link.visual.material.color != null) {
            URDFLink.Material.Vector4f color = link.visual.material.color;
            r = (int)(color.x * 255);
            g = (int)(color.y * 255);
            b = (int)(color.z * 255);
            a = (int)(color.w * 255);
        }

        int blockLight = Math.max((packedLight & 0xFFFF), 0xA0);
        int skyLight = Math.max((packedLight >> 16) & 0xFFFF, 0xA0);

        for (STLLoader.Triangle tri : mesh.triangles) {
            for (int i = 2; i >= 0; i--) {
                Vector3f v = tri.vertices[i];
                Vector3f n = tri.normal;

                float nx = FLIP_NORMALS ? -n.x : n.x;
                float ny = FLIP_NORMALS ? -n.y : n.y;
                float nz = FLIP_NORMALS ? -n.z : n.z;

                vc.addVertex(matrix, v.x, v.y, v.z)
                        .setColor(r, g, b, a)
                        .setUv(0.5f, 0.5f)
                        .setUv2(blockLight, skyLight)
                        .setNormal(nx, ny, nz);
            }
        }
    }

    private void applyLinkOriginTransform(URDFLink.Origin origin, PoseStack poseStack) {
        poseStack.translate(origin.xyz.x, origin.xyz.y, origin.xyz.z);
        if (origin.rpy.x != 0f || origin.rpy.y != 0f || origin.rpy.z != 0f) {
            poseStack.mulPose(origin.getQuaternion());
        }
    }

    private void applyJointOriginTransform(URDFJoint.Origin origin, PoseStack poseStack) {
        poseStack.translate(origin.xyz.x, origin.xyz.y, origin.xyz.z);
        if (origin.rpy.x != 0f || origin.rpy.y != 0f || origin.rpy.z != 0f) {
            Quaternionf qx = new Quaternionf().rotateX(origin.rpy.x);
            Quaternionf qy = new Quaternionf().rotateY(origin.rpy.y);
            Quaternionf qz = new Quaternionf().rotateZ(origin.rpy.z);
            poseStack.mulPose(qz.mul(qy).mul(qx));
        }
    }

    private void applyJointTransform(URDFJoint joint, PoseStack poseStack) {
        if (joint.origin != null) {
            applyJointOriginTransform(joint.origin, poseStack);
        }
        if (joint.isMovable()) {
            applyJointMotion(joint, poseStack);
        }
    }

    private void applyJointMotion(URDFJoint joint, PoseStack poseStack) {
        if (joint == null) return;

        switch (joint.type) {
            case REVOLUTE:
            case CONTINUOUS: {
                Vector3f axis;
                if (joint.axis == null || joint.axis.xyz == null ||
                        joint.axis.xyz.lengthSquared() < 1e-12f) {
                    axis = new Vector3f(1, 0, 0);
                } else {
                    axis = new Vector3f(joint.axis.xyz);
                    if (axis.lengthSquared() < 1e-12f) axis.set(1, 0, 0);
                    else axis.normalize();
                }
                Quaternionf quat = new Quaternionf().rotateAxis(joint.currentPosition, axis.x, axis.y, axis.z);
                poseStack.mulPose(quat);
                break;
            }
            case PRISMATIC: {
                Vector3f axis;
                if (joint.axis == null || joint.axis.xyz == null ||
                        joint.axis.xyz.lengthSquared() < 1e-12f) {
                    axis = new Vector3f(1, 0, 0);
                } else {
                    axis = new Vector3f(joint.axis.xyz);
                    if (axis.lengthSquared() < 1e-12f) axis.set(1, 0, 0);
                    else axis.normalize();
                }
                Vector3f t = axis.mul(joint.currentPosition);
                poseStack.translate(t.x, t.y, t.z);
                break;
            }
            default:
                break;
        }
    }

    // ===== IMMDModel 구현 =====
    @Override public void ChangeAnim(long anim, long layer) { }
    @Override public void ResetPhysics() { 
        logger.info("ResetPhysics called");
        // TODO: ODE world 리셋 구현 필요시 여기 추가
    }
    @Override public long GetModelLong() { return 0; }
    @Override public String GetModelDir() { return modelDir; }

    public static URDFModelOpenGLWithSTL Create(String urdfPath, String modelDir) {
        File urdfFile = new File(urdfPath);
        if (!urdfFile.exists()) return null;
        URDFModel robot = URDFParser.parse(urdfFile);
        if (robot == null || robot.rootLinkName == null) return null;
        return new URDFModelOpenGLWithSTL(robot, modelDir);
    }

    public URDFModel getRobotModel() {
        return robotModel;
    }

    // ===== 내부 유틸 =====
    private URDFJoint getJointByName(String name) {
        if (name == null) return null;
        for (URDFJoint j : robotModel.joints) {
            if (name.equals(j.name)) return j;
        }
        return null;
    }

    /**
     * ✅ 대소문자 무시하고 관절 찾기
     */
    private URDFJoint getJointByNameIgnoreCase(String name) {
        if (name == null) return null;
        for (URDFJoint j : robotModel.joints) {
            if (name.equalsIgnoreCase(j.name)) return j;
        }
        return null;
    }

    // ===== 업라이트 보정 유틸 =====
    private static Quaternionf makeUprightQuat(Vector3f srcUp, Vector3f srcFwd,
                                               Vector3f dstUp, Vector3f dstFwd) {
        Vector3f su = new Vector3f(srcUp).normalize();
        Vector3f sf = new Vector3f(srcFwd).normalize();
        Vector3f du = new Vector3f(dstUp).normalize();
        Vector3f df = new Vector3f(dstFwd).normalize();

        Quaternionf qUp = fromToQuat(su, du);
        Vector3f sf1 = sf.rotate(new Quaternionf(qUp));

        Vector3f sf1p = new Vector3f(sf1).sub(new Vector3f(du).mul(sf1.dot(du)));
        Vector3f dfp = new Vector3f(df).sub(new Vector3f(du).mul(df.dot(du)));
        if (sf1p.lengthSquared() < 1e-10f || dfp.lengthSquared() < 1e-10f) {
            return qUp.normalize();
        }
        sf1p.normalize();
        dfp.normalize();

        float cos = clamp(sf1p.dot(dfp), -1f, 1f);
        float angle = (float) Math.acos(cos);
        Vector3f cross = sf1p.cross(dfp, new Vector3f());
        if (cross.dot(du) < 0) angle = -angle;

        Quaternionf qFwd = new Quaternionf().fromAxisAngleRad(du, angle);
        return qFwd.mul(qUp).normalize();
    }

    private static Quaternionf fromToQuat(Vector3f a, Vector3f b) {
        Vector3f v1 = new Vector3f(a).normalize();
        Vector3f v2 = new Vector3f(b).normalize();
        float dot = clamp(v1.dot(v2), -1f, 1f);

        if (dot > 1.0f - 1e-6f) return new Quaternionf();
        if (dot < -1.0f + 1e-6f) {
            Vector3f axis = pickAnyPerp(v1).normalize();
            return new Quaternionf().fromAxisAngleRad(axis, (float) Math.PI);
        }
        Vector3f axis = v1.cross(v2, new Vector3f()).normalize();
        float angle = (float) Math.acos(dot);
        return new Quaternionf().fromAxisAngleRad(axis, angle);
    }

    private static Vector3f pickAnyPerp(Vector3f v) {
        Vector3f x = new Vector3f(1, 0, 0), y = new Vector3f(0, 1, 0), z = new Vector3f(0, 0, 1);
        float dx = Math.abs(v.dot(x)), dy = Math.abs(v.dot(y)), dz = Math.abs(v.dot(z));
        return (dx < dy && dx < dz) ? x : ((dy < dz) ? y : z);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (Math.min(v, hi));
    }
}
