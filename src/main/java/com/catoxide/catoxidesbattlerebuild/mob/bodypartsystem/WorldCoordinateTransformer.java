// WorldCoordinateTransformer.java
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class WorldCoordinateTransformer {
    private final ModularZombie parent;

    public WorldCoordinateTransformer(ModularZombie parent) {
        this.parent = parent;
    }

    /**
     * 将模型局部坐标转换为世界坐标
     */
    public Vec3 transformToWorld(Vec3 localPosition, BoneTransform entityTransform) {
        // 应用实体级别的变换（位置、旋转、缩放）
        Vector3f transformed = new Vector3f(
                (float) localPosition.x,
                (float) localPosition.y,
                (float) localPosition.z
        );

        // 应用实体缩放
        transformed.mul(entityTransform.scale);

        // 应用实体旋转
        if (entityTransform.rotation != null && !isIdentityQuaternion(entityTransform.rotation)) {
            transformed = entityTransform.rotation.transform(transformed);
        }

        // 应用实体位置
        return new Vec3(
                entityTransform.position.x + transformed.x,
                entityTransform.position.y + transformed.y,
                entityTransform.position.z + transformed.z
        );
    }

    /**
     * 变换顶点考虑骨骼枢轴点
     */
    public Vec3 transformVertexWithPivot(Vertex vertex, BoneTransform animationTransform, float[] pivot) {
        float scaleFactor = 1.0f / 16.0f;

        // 1. 获取顶点在标准姿势下的位置（相对于枢轴点）
        Vector3f standardPos = new Vector3f(
                (vertex.x - pivot[0]) * scaleFactor,
                (vertex.y - pivot[1]) * scaleFactor,
                (vertex.z - pivot[2]) * scaleFactor
        );

        // 2. 应用动画旋转
        Vector3f rotatedPos = animationTransform.rotation.transform(standardPos);

        // 3. 应用缩放
        rotatedPos.mul(animationTransform.scale);

        // 4. 加上标准姿势的枢轴点位置和实体位置
        return new Vec3(
                animationTransform.position.x + rotatedPos.x + pivot[0] * scaleFactor,
                animationTransform.position.y + rotatedPos.y + pivot[1] * scaleFactor,
                animationTransform.position.z + rotatedPos.z + pivot[2] * scaleFactor
        );
    }

    /**
     * 获取实体级别的变换（位置、朝向等）
     */
    public BoneTransform getEntityWorldTransform() {
        return new BoneTransform(
                parent.position(),
                getEntityRotation(),
                new Vector3f(1, 1, 1) // 实体默认不缩放
        );
    }

    private Quaternionf getEntityRotation() {
        // 根据实体朝向创建旋转四元数
        float yaw = parent.getYRot();
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-yaw)); // Minecraft使用左手坐标系
    }

    private boolean isIdentityQuaternion(Quaternionf rotation) {
        return rotation.x == 0 && rotation.y == 0 && rotation.z == 0 && rotation.w == 1;
    }
}