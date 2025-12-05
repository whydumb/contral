package com.kAIS.KAIMyEntity.urdf;

import com.kAIS.KAIMyEntity.renderer.IMMDModel;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * URDF 모델 렌더링 (STL 메시 포함)
 */
public class URDFModelOpenGLWithSTL implements IMMDModel {
    private static final Logger logger = LogManager.getLogger();
    private static int renderCount = 0;

    private URDFModel robotModel;
    private String modelDir;

    private final Map<String, STLLoader.STLMesh> meshCache = new HashMap<>();

    private static final float GLOBAL_SCALE = 5.0f;
    private static final boolean FLIP_NORMALS = true;

    private static final Vector3f SRC_UP  = new Vector3f(0, 0, 1);
    private static final Vector3f SRC_FWD = new Vector3f(1, 0, 0);
    private static final boolean FORWARD_NEG_Z = true;
    private static final Vector3f DST_UP  = new Vector3f(0, 1, 0);
    private static final Vector3f DST_FWD = FORWARD_NEG_Z ? new Vector3f(0, 0, -1) : new Vector3f(0, 0, 1);
    private static final Quaternionf Q_ROS2MC = makeUprightQuat(SRC_UP, SRC_FWD, DST_UP, DST_FWD);

    // ✅ 관절 이름 매핑 (VMD 이름 → URDF 이름)
    private final Map<String, String> jointNameMapping = new HashMap<>();
    
    // ✅ 디버그용 플래그
    private boolean jointMappingInitialized = false;

    public URDFModelOpenGLWithSTL(URDFModel robotModel, String modelDir) {
        this.robotModel = robotModel;
        this.modelDir = modelDir;
        logger.info("=== URDF renderer Created ===");
        loadAllMeshes();
        initJointNameMapping();
    }

    /**
     * ✅ 관절 이름 매핑 초기화 - VMD 이름을 URDF 이름으로 변환
     */
    private void initJointNameMapping() {
        logger.info("=== Initializing Joint Name Mapping ===");
        
        // URDF에 존재하는 모든 관절 이름 수집
        Set<String> urdfJointNames = new HashSet<>();
        for (URDFJoint j : robotModel.joints) {
            urdfJointNames.add(j.name);
            urdfJointNames.add(j.name.toLowerCase());
        }
        
        // VMD 표준 이름 → 가능한 URDF 이름들
        Map<String, String[]> vmdToUrdfCandidates = new HashMap<>();
        
        // 머리
        vmdToUrdfCandidates.put("head_pan", new String[]{"head_pan", "HeadYaw", "head_yaw", "Neck", "neck"});
        vmdToUrdfCandidates.put("head_tilt", new String[]{"head_tilt", "HeadPitch", "head_pitch", "Head", "head"});
        
        // 왼팔
        vmdToUrdfCandidates.put("l_sho_pitch", new String[]{"l_sho_pitch", "LShoulderPitch", "l_shoulder_pitch", "LeftShoulderPitch", "left_shoulder_pitch"});
        vmdToUrdfCandidates.put("l_sho_roll", new String[]{"l_sho_roll", "LShoulderRoll", "l_shoulder_roll", "LeftShoulderRoll", "left_shoulder_roll"});
        vmdToUrdfCandidates.put("l_el", new String[]{"l_el", "LElbowYaw", "l_elbow", "LeftElbow", "left_elbow", "LElbowRoll"});
        
        // 오른팔
        vmdToUrdfCandidates.put("r_sho_pitch", new String[]{"r_sho_pitch", "RShoulderPitch", "r_shoulder_pitch", "RightShoulderPitch", "right_shoulder_pitch"});
        vmdToUrdfCandidates.put("r_sho_roll", new String[]{"r_sho_roll", "RShoulderRoll", "r_shoulder_roll", "RightShoulderRoll", "right_shoulder_roll"});
        vmdToUrdfCandidates.put("r_el", new String[]{"r_el", "RElbowYaw", "r_elbow", "RightElbow", "right_elbow", "RElbowRoll"});
        
        // 하체 (옵션)
        vmdToUrdfCandidates.put("l_hip_pitch", new String[]{"l_hip_pitch", "LHipPitch", "LeftHipPitch"});
        vmdToUrdfCandidates.put("r_hip_pitch", new String[]{"r_hip_pitch", "RHipPitch", "RightHipPitch"});
        
        // 매핑 생성
        for (Map.Entry<String, String[]> entry : vmdToUrdfCandidates.entrySet()) {
            String vmdName = entry.getKey();
            for (String candidate : entry.getValue()) {
                // 정확히 일치하는 관절 찾기
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
        
        // 매핑 안 된 VMD 이름은 그대로 사용 (직접 매칭 시도)
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

    public void tickUpdate(float dt) {
        // 현재는 빈 메서드 - 필요시 물리/애니메이션 업데이트
    }

    /**
     * ✅ 관절 목표값 설정 (현재는 즉시 반영)
     */
    public void setJointTarget(String name, float value) {
        setJointPreview(name, value);
    }

    /**
     * ✅ 여러 관절 한번에 설정
     */
    public void setJointTargets(Map<String, Float> values) {
        for (Map.Entry<String, Float> entry : values.entrySet()) {
            setJointPreview(entry.getKey(), entry.getValue());
        }
    }

    /**
     * ✅ 즉시 반영(프리뷰): 관절 이름 매핑 포함
     */
    public void setJointPreview(String name, float value) {
        // 1. 매핑된 이름으로 변환
        String mappedName = jointNameMapping.getOrDefault(name, name);
        
        // 2. 관절 찾기
        URDFJoint j = getJointByName(mappedName);
        
        // 3. 못 찾으면 원본 이름으로 재시도
        if (j == null && !mappedName.equals(name)) {
            j = getJointByName(name);
        }
        
        // 4. 대소문자 무시하고 재시도
        if (j == null) {
            j = getJointByNameIgnoreCase(name);
        }
        
        if (j != null) {
            j.currentPosition = value;
            // 디버그 (처음 몇 번만)
            if (renderCount < 5) {
                logger.info("✓ Joint '{}' -> '{}' = {} rad ({} deg)", 
                    name, j.name, value, Math.toDegrees(value));
            }
        } else {
            // 못 찾은 경우 경고 (처음 몇 번만)
            if (renderCount < 5) {
                logger.warn("✗ Joint NOT FOUND: '{}' (mapped: '{}')", name, mappedName);
            }
        }
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

    @Override
    public void Render(Entity entityIn, float entityYaw, float entityPitch,
                       Vector3f entityTrans, float tickDelta, PoseStack poseStack, int packedLight) {

        renderCount++;
        if (renderCount % 120 == 1) {
            logger.info("=== URDF RENDER #{} ===", renderCount);
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();

        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.solid());

        if (robotModel.rootLinkName != null) {
            poseStack.pushPose();
            poseStack.scale(GLOBAL_SCALE, GLOBAL_SCALE, GLOBAL_SCALE);
            poseStack.mulPose(new Quaternionf(Q_ROS2MC));
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
    @Override public void ResetPhysics() { logger.info("ResetPhysics called"); }
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
