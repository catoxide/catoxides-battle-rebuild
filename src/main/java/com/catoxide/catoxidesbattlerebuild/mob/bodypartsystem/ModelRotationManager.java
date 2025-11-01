// ModelRotationManager.java
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class ModelRotationManager {
    private Quaternionf globalRotation = new Quaternionf();
    private Vector3f globalRotationCenter = new Vector3f();

    /**
     * 设置模型整体旋转
     */
    public void setGlobalRotation(Quaternionf rotation, Vector3f rotationCenter) {
        this.globalRotation = new Quaternionf(rotation);
        this.globalRotationCenter = new Vector3f(rotationCenter);
    }

    /**
     * 应用整体旋转到局部坐标
     */
    public Vec3 applyGlobalRotation(Vec3 localPosition) {
        if (isIdentityQuaternion(globalRotation)) {
            return localPosition;
        }

        // 相对于旋转中心进行旋转
        Vector3f relativePos = new Vector3f(
                (float) (localPosition.x - globalRotationCenter.x),
                (float) (localPosition.y - globalRotationCenter.y),
                (float) (localPosition.z - globalRotationCenter.z)
        );

        // 应用旋转
        Vector3f rotated = globalRotation.transform(relativePos);

        // 移回原位置
        return new Vec3(
                rotated.x + globalRotationCenter.x,
                rotated.y + globalRotationCenter.y,
                rotated.z + globalRotationCenter.z
        );
    }

    /**
     * 应用整体旋转到骨骼变换
     */
    public BoneTransform applyGlobalRotation(BoneTransform boneTransform) {
        if (isIdentityQuaternion(globalRotation)) {
            return boneTransform;
        }

        // 组合旋转：全局旋转 * 骨骼旋转
        Quaternionf combinedRotation = new Quaternionf(globalRotation)
                .mul(boneTransform.rotation);

        // 应用全局旋转到位置
        Vec3 rotatedPosition = applyGlobalRotation(boneTransform.position);

        return new BoneTransform(rotatedPosition, combinedRotation, boneTransform.scale);
    }

    public Quaternionf getGlobalRotation() {
        return new Quaternionf(globalRotation);
    }

    public Vector3f getGlobalRotationCenter() {
        return new Vector3f(globalRotationCenter);
    }

    private boolean isIdentityQuaternion(Quaternionf rotation) {
        return rotation.x == 0 && rotation.y == 0 && rotation.z == 0 && rotation.w == 1;
    }
}