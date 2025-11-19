
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class CollisionBoxTransform {
    // 实体级别的变换（整个生物）
    public final Quaternionf entityRotation; // 生物整体旋转（yaw/pitch）
    public final Vec3 entityPosition;        // 生物世界位置

    // 骨骼级别的变换（局部动画）
    public final Quaternionf boneRotation;   // 骨骼局部旋转（动画）
    public final Vector3f boneScale;         // 骨骼缩放
    public final Vec3 boneOffset;            // 骨骼位置偏移

    // 枢轴点（骨骼的局部枢轴点）
    public final float[] pivot;

    public CollisionBoxTransform(Quaternionf entityRotation, Vec3 entityPosition,
                                 Quaternionf boneRotation, Vector3f boneScale,
                                 Vec3 boneOffset, float[] pivot) {
        this.entityRotation = entityRotation;
        this.entityPosition = entityPosition;
        this.boneRotation = boneRotation;
        this.boneScale = boneScale;
        this.boneOffset = boneOffset;
        this.pivot = pivot;
    }

    // 从 BoneTransform 和实体数据创建 CollisionBoxTransform
    public static CollisionBoxTransform fromBoneTransform(BoneTransform boneTransform,
                                                          ModularZombie entity,
                                                          float[] pivot) {
        // 获取实体旋转（从实体的yaw/pitch创建四元数）
        Quaternionf entityRot = createEntityRotation(entity);

        return new CollisionBoxTransform(
                entityRot,
                entity.position(),
                boneTransform.rotation,
                boneTransform.scale,
                boneTransform.position,
                pivot
        );
    }

    // 从实体yaw/pitch创建旋转四元数
    private static Quaternionf createEntityRotation(ModularZombie entity) {
        Quaternionf rotation = new Quaternionf();

        // 应用yaw旋转（Y轴）
        float yawRad = (float) Math.toRadians(-entity.getYRot()); // Minecraft的yaw需要取反
        rotation.rotationY(yawRad);

        // 应用pitch旋转（X轴）
        float pitchRad = (float) Math.toRadians(entity.getXRot());
        Quaternionf pitchRot = new Quaternionf().rotationX(pitchRad);
        rotation.mul(pitchRot);

        return rotation;
    }

    // 单位变换（用于调试）
    public static CollisionBoxTransform identity(ModularZombie entity) {
        return new CollisionBoxTransform(
                new Quaternionf(),
                entity.position(),
                new Quaternionf(),
                new Vector3f(1, 1, 1),
                Vec3.ZERO,
                new float[]{0, 0, 0}
        );
    }
}