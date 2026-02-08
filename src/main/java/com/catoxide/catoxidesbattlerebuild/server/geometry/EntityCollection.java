package com.catoxide.catoxidesbattlerebuild.server.geometry;

import com.catoxide.catoxidesbattlerebuild.server.models.ModelCollection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.AnimationProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EntityCollection(
        UUID entityId,                    // 实体UUID
        ResourceLocation modelLocation,   // 模型位置
        ModelCollection modelCollection,  // 引用的ModelCollection
        Entity entity,                    // 实体引用

        // 动态数据字段
        Map<String, Matrix4f> boneMatrices,       // 骨骼变换矩阵
        Map<String, List<Vec3>> cubeVertices, // cube顶点数据
        long lastUpdateTime,               // 最后更新时间
        int updateCount                    // 更新次数
) {
    /**
     * 创建新的EntityCollection（使用工厂方法）
     * 注意：这个方法应该通过EntityCollectionFactory调用
     */
    static EntityCollection create(
            UUID entityId,
            ResourceLocation modelLocation,
            ModelCollection modelCollection,
            Entity entity
    ) {
        return new EntityCollection(
                entityId,
                modelLocation,
                modelCollection,
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
    public EntityCollection withDynamicData(
            Map<String, Matrix4f> boneMatrices,
            Map<String, List<Vec3>> cubeVertices
    ) {
        return new EntityCollection(
                this.entityId,
                this.modelLocation,
                this.modelCollection,
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
     * 获取动画处理器（从ModelCollection中获取）
     */
    public AnimationProcessor<GeoAnimatable> animationProcessor() {
        return modelCollection != null ? modelCollection.animationProcessor() : null;
    }

    /**
     * 获取烘焙模型（从ModelCollection中获取）
     */
    public software.bernie.geckolib.cache.object.BakedGeoModel bakedModel() {
        return modelCollection != null ? modelCollection.bakedModel() : null;
    }

    /**
     * 获取核心模型（从ModelCollection中获取）
     */
    public software.bernie.geckolib.core.animatable.model.CoreGeoModel coreModel() {
        return modelCollection != null ? modelCollection.coreModel() : null;
    }

    /**
     * 获取GeoCube的键名
     */
    public static String getCubeKey(String boneName, GeoCube cube) {
        return boneName + ":" + cube.hashCode();
    }

    /**
     * 转换为字符串（调试用）
     */
    @Override
    public String toString() {
        return String.format("EntityCollection[entityId=%s, model=%s, updates=%d]",
                entityId, modelLocation, updateCount);
    }
}