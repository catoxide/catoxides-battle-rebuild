package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.AnimationProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EnhancedEntityCollection(
        UUID entityId,                    // 实体UUID
        ResourceLocation modelLocation,   // 模型位置
        AnimationProcessor<GeoAnimatable> animationProcessor, // 动画处理器
        Entity entity,                    // 实体引用（弱引用，仅用于有效性检查）

        // 动态数据字段
        Map<String, Matrix4f> boneMatrices,       // 骨骼变换矩阵
        Map<String, List<Vector3f>> cubeVertices, // cube顶点数据
        long lastUpdateTime,               // 最后更新时间
        int updateCount                    // 更新次数
) {
    /**
     * 创建新的EnhancedEntityCollection（无动态数据）
     */
    public static EnhancedEntityCollection create(Entity entity, ResourceLocation modelLocation) {
        AnimationProcessor<GeoAnimatable> processor =
                ServerGeoModelManager.getInstance().getOrCreateAnimationProcessor(modelLocation);

        return new EnhancedEntityCollection(
                entity.getUUID(),
                modelLocation,
                processor,
                entity,
                null, // boneMatrices
                null, // cubeVertices
                System.currentTimeMillis(),
                0
        );
    }

    /**
     * 更新动态数据
     */
    public EnhancedEntityCollection withDynamicData(
            Map<String, Matrix4f> boneMatrices,
            Map<String, List<Vector3f>> cubeVertices) {
        return new EnhancedEntityCollection(
                this.entityId,
                this.modelLocation,
                this.animationProcessor,
                this.entity,
                boneMatrices,
                cubeVertices,
                System.currentTimeMillis(),
                this.updateCount + 1
        );
    }

    /**
     * 检查关联是否有效
     */
    public boolean isValid() {
        return entity != null &&
                !entity.isRemoved() &&
                entity.isAlive() &&
                entity.level() != null;
    }

    /**
     * 获取GeoCube的键名
     */
    public static String getCubeKey(String boneName, GeoCube cube) {
        return boneName + ":" + cube.hashCode();
    }
}