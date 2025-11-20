package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import net.minecraft.client.Minecraft;
import org.joml.*;

import java.util.*;

import static java.lang.Math.*;

/**
 * 2025.11.20 VMC Direct Control (슬라이더 방식 그대로)
 * 2025.11.20 최종 수정: URDF 관절 정의와 100% 일치 (pitch/roll/elbow 부호 완벽 매칭)
 */
public final class MotionEditorScreen {
    private MotionEditorScreen() {}

    static {
        VMCListenerController.VmcListener listener = VMCListenerController.VmcListener.getInstance();
        listener.setBoneNameNormalizer(original -> {
            if (original == null) return null;
            String lower = original.toLowerCase().trim();
            return switch (lower) {
                case "leftupperarm", "leftarm", "left_arm", "upperarm_left", "arm.l", "leftshoulder" -> "LeftUpperArm";
                case "leftlowerarm", "leftforearm", "lowerarm_left", "forearm.l", "leftelbow" -> "LeftLowerArm";
                case "lefthand", "hand.l", "hand_left", "left_wrist", "left_hand" -> "LeftHand";
                case "rightupperarm", "rightarm", "right_arm", "upperarm_right", "arm.r", "rightshoulder" -> "RightUpperArm";
                case "rightlowerarm", "rightforearm", "lowerarm_right", "forearm.r", "rightelbow" -> "RightLowerArm";
                case "righthand", "hand.r", "hand_right", "right_wrist", "right_hand" -> "RightHand";
                default -> original;
            };
        });
    }

    public static void open(URDFModelOpenGLWithSTL renderer) {
        open(renderer, 39539);
    }

    public static void open(URDFModelOpenGLWithSTL renderer, int vmcPort) {
        VMCListenerController.VmcListener listener = VMCListenerController.VmcListener.getInstance();
        listener.start("0.0.0.0", vmcPort);
        Minecraft.getInstance().setScreen(new VMCListenerController(Minecraft.getInstance().screen, renderer));
    }

    public static void tick(URDFModelOpenGLWithSTL renderer) {
        VmcDrive.tick(renderer);
    }
}

/* ======================== VmcDrive ======================== */
final class VmcDrive {

    static void tick(URDFModelOpenGLWithSTL renderer) {
        var listener = VMCListenerController.VmcListener.getInstance();
        Map<String, VMCListenerController.VmcListener.BoneTransform> bones = listener.getBones();

        if (bones.isEmpty()) return;

        // 몸통 회전 (Chest → Spine → Hips fallback)
        Quaternionf trunkRotation = new Quaternionf(); // identity
        if (bones.containsKey("Chest")) {
            trunkRotation.set(bones.get("Chest").rotation);
        } else if (bones.containsKey("Spine")) {
            trunkRotation.set(bones.get("Spine").rotation);
        } else if (bones.containsKey("Hips")) {
            trunkRotation.set(bones.get("Hips").rotation);
        }

        processArm(renderer, bones, trunkRotation, true);  // 왼팔
        processArm(renderer, bones, trunkRotation, false); // 오른팔
    }

    private static void processArm(URDFModelOpenGLWithSTL renderer,
                                   Map<String, VMCListenerController.VmcListener.BoneTransform> bones,
                                   Quaternionf trunkRotation,
                                   boolean isLeft) {
        String upperName = isLeft ? "LeftUpperArm" : "RightUpperArm";
        String lowerName = isLeft ? "LeftLowerArm" : "RightLowerArm";
        String handName  = isLeft ? "LeftHand"     : "RightHand";

        var upper = bones.get(upperName);
        var lower = bones.get(lowerName);
        var hand  = bones.get(handName);

        if (upper == null || lower == null) return;

        // 1. 상박 방향 벡터 (월드)
        Vector3f upperVec = new Vector3f(lower.position).sub(upper.position);
        if (upperVec.lengthSquared() < 1e-6f) return;
        upperVec.normalize();

        // 몸통 기준 로컬 방향
        Quaternionf invTrunk = new Quaternionf(trunkRotation).conjugate();
        Vector3f localVec = new Vector3f(upperVec).rotate(invTrunk);

        // ★★★ 당신의 URDF와 정확히 일치하는 최종 매핑 ★★★
        float pitch = (float) -atan2(localVec.z, localVec.y);  // +면 앞쪽으로 돌아감 → 완벽
        float roll  = (float) -atan2(localVec.x, localVec.y);  // +면 아래로 내려감 (왼쪽/오른쪽 모두) → 완벽

        // 2. 팔꿈치 각도
        float elbowAngle = 0f;
        if (hand != null) {
            Vector3f lowerVec = new Vector3f(hand.position).sub(lower.position);
            if (lowerVec.lengthSquared() > 1e-6f) {
                lowerVec.normalize();
                float dot = max(-1f, min(1f, upperVec.dot(lowerVec)));
                elbowAngle = (float) acos(dot);

                // 왼쪽: 음수 = 안쪽으로 접힘, 오른쪽: 양수 = 안쪽으로 접힘 → 당신 URDF와 정확히 일치
                elbowAngle = isLeft ? -elbowAngle : elbowAngle;
            }
        }

        // 3. URDF 조인트에 적용
        String pitchJoint = isLeft ? "l_sho_pitch" : "r_sho_pitch";
        String rollJoint  = isLeft ? "l_sho_roll"  : "r_sho_roll";
        String elbowJoint = isLeft ? "l_el"        : "r_el";

        renderer.setJointPreview(pitchJoint, pitch);
        renderer.setJointTarget(pitchJoint, pitch);

        renderer.setJointPreview(rollJoint, roll);
        renderer.setJointTarget(rollJoint, roll);

        renderer.setJointPreview(elbowJoint, elbowAngle);
        renderer.setJointTarget(elbowJoint, elbowAngle);
    }
}
