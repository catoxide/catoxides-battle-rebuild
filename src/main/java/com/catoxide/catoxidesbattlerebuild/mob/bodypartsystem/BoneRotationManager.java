// BoneRotationManager.java
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.HashMap;
import java.util.Map;

public class BoneRotationManager {
    private final ModularZombie parent;
    private final Map<String, BoneTransform> boneTransforms = new HashMap<>();
    private final Map<String, Quaternionf> additionalRotations = new HashMap<>();

    public BoneRotationManager(ModularZombie parent) {
        this.parent = parent;
    }

    /**
     * 获取骨骼的完整变换（动画 + 额外旋转）
     */
    public BoneTransform getBoneTransform(String boneName) {
        // 获取基础动画变换
        BoneTransform animationTransform = parent.getServerAnimationSystem()
                .getBoneTransform(boneName);

        // 应用额外旋转
        BoneTransform finalTransform = applyAdditionalRotation(boneName, animationTransform);

        // 创建包含实体旋转的变换
        return BoneTransform.fromEntity(parent, finalTransform.rotation, finalTransform.position);
    }

    /**
     * 为骨骼添加额外旋转（用于特效、受伤反应等）
     */
    public void addBoneRotation(String boneName, Quaternionf additionalRotation) {
        Quaternionf current = additionalRotations.get(boneName);
        if (current != null) {
            current.mul(additionalRotation);
        } else {
            additionalRotations.put(boneName, new Quaternionf(additionalRotation));
        }
    }

    /**
     * 设置骨骼的固定旋转（覆盖动画）
     */
    public void setBoneRotation(String boneName, Quaternionf rotation) {
        additionalRotations.put(boneName, new Quaternionf(rotation));
    }

    /**
     * 重置骨骼的额外旋转
     */
    public void resetBoneRotation(String boneName) {
        additionalRotations.remove(boneName);
    }

    /**
     * 重置所有额外旋转
     */
    public void resetAllRotations() {
        additionalRotations.clear();
    }

    /**
     * 获取骨骼在标准姿势下的变换
     */
    public BoneTransform getStandardPoseTransform(String boneName) {
        GeometryModel.Bone bone = parent.getBodyPartManager().getGeometryModel().bones.get(boneName);
        if (bone != null) {
            float scaleFactor = 1.0f / 16.0f;
            Vec3 standardPos = new Vec3(
                    bone.pivot[0] * scaleFactor,
                    bone.pivot[1] * scaleFactor,
                    bone.pivot[2] * scaleFactor
            );
            return new BoneTransform(standardPos, new Quaternionf(), new Vector3f(1, 1, 1));
        }
        return BoneTransform.IDENTITY;
    }

    private BoneTransform applyAdditionalRotation(String boneName, BoneTransform baseTransform) {
        Quaternionf additional = additionalRotations.get(boneName);
        if (additional == null) {
            return baseTransform;
        }

        // 组合旋转：基础旋转 * 额外旋转
        Quaternionf combinedRotation = new Quaternionf(baseTransform.rotation)
                .mul(additional);

        return new BoneTransform(
                baseTransform.position,
                combinedRotation,
                baseTransform.scale
        );
    }

    /**
     * 更新所有骨骼变换（每tick调用）
     */
    public void updateBoneTransforms() {
        boneTransforms.clear();

        for (String boneName : getCollisionBones()) {
            // 使用包含实体旋转的变换
            boneTransforms.put(boneName, getBoneTransform(boneName));
        }
    }
    private java.util.List<String> getCollisionBones() {
        return java.util.Arrays.asList("head", "body", "left_arm", "right_arm", "left_leg", "right_leg");
    }
}