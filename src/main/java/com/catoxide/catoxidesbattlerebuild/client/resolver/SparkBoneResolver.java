package com.catoxide.catoxidesbattlerebuild.client.resolver;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import cn.solarmoon.spark_core.animation.IEntityAnimatable;
import cn.solarmoon.spark_core.animation.model.BonePose;
import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.animation.model.ModelPose;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class SparkBoneResolver {

    /**
     * 使用 Spark-Core 获取骨骼的世界变换矩阵
     * @param entity 实体
     * @param boneName 骨骼名称
     * @param partialTicks 部分tick
     * @return 世界变换矩阵
     */
    public static Matrix4f resolveBoneWorldMatrix(Entity entity, String boneName, float partialTicks) {
        if (!(entity instanceof IEntityAnimatable<?> animatable)) {
            LogManager.clientWarn("SparkBoneResolver", "Entity is not IEntityAnimatable: {}", entity.getType());
            return new Matrix4f();
        }

        ModelInstance model = animatable.getModelController().getModel();
        if (model == null) {
            LogManager.clientWarn("SparkBoneResolver", "Model is null for entity: {}", entity.getType());
            return new Matrix4f();
        }

        ModelPose pose = model.getPose();
        try {
            BonePose bonePose = pose.getBonePose(boneName);
            return bonePose.getWorldBoneMatrix(partialTicks);
        } catch (Exception e) {
            LogManager.clientWarn("SparkBoneResolver", "Failed to get bone pose for {}: {}", boneName, e.getMessage());
            return new Matrix4f();
        }
    }

    /**
     * 使用 Spark-Core 获取骨骼的局部变换矩阵
     * @param entity 实体
     * @param boneName 骨骼名称
     * @param partialTicks 部分tick
     * @return 局部变换矩阵
     */
    public static Matrix4f resolveBoneLocalMatrix(Entity entity, String boneName, float partialTicks) {
        if (!(entity instanceof IEntityAnimatable<?> animatable)) {
            LogManager.clientWarn("SparkBoneResolver", "Entity is not IEntityAnimatable: {}", entity.getType());
            return new Matrix4f();
        }

        ModelInstance model = animatable.getModelController().getModel();
        if (model == null) {
            LogManager.clientWarn("SparkBoneResolver", "Model is null for entity: {}", entity.getType());
            return new Matrix4f();
        }

        ModelPose pose = model.getPose();
        try {
            BonePose bonePose = pose.getBonePose(boneName);
            return bonePose.getLocalTransformMatrix(partialTicks);
        } catch (Exception e) {
            LogManager.clientWarn("SparkBoneResolver", "Failed to get bone pose for {}: {}", boneName, e.getMessage());
            return new Matrix4f();
        }
    }

    /**
     * 使用 Spark-Core 获取骨骼的世界位置
     * @param entity 实体
     * @param boneName 骨骼名称
     * @param partialTicks 部分tick
     * @return 世界位置
     */
    public static Vec3 resolveBoneWorldPosition(Entity entity, String boneName, float partialTicks) {
        Matrix4f matrix = resolveBoneWorldMatrix(entity, boneName, partialTicks);
        Vector3f pos = new Vector3f();
        matrix.transformPosition(pos);
        return new Vec3(pos.x(), pos.y(), pos.z());
    }

    /**
     * 使用 Spark-Core 的插值功能（包装方法，保持 API 兼容）
     */
    public static Vec3 interpolate(Vec3 start, Vec3 end, float alpha) {
        return InterpolationSystem.interpolate(start, end, alpha);
    }

    public static Quaternionf interpolate(Quaternionf start, Quaternionf end, float alpha) {
        return InterpolationSystem.interpolate(start, end, alpha);
    }

    public static Matrix4f interpolate(Matrix4f start, Matrix4f end, float alpha) {
        return InterpolationSystem.interpolate(start, end, alpha);
    }
}
