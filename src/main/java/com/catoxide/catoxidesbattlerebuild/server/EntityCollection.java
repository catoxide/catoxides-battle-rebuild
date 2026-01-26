package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.AnimationProcessor;

import java.util.UUID;

/**
 * 实体-模型关联记录（一次性建立，持续有效）
 * 使用record实现不可变关联
 */
public record EntityCollection(
        UUID entityId,
        ResourceLocation modelLocation,
        AnimationProcessor<GeoAnimatable> animationProcessor
) {
    /**
     * 检查关联是否仍然有效
     */
    public boolean isValid(Entity entity) {
        return entity != null &&
                entity.isAlive() &&
                !entity.isRemoved() &&
                entity.getUUID().equals(entityId);
    }

    /**
     * 更新实体动画
     */
    public void updateAnimation(GeoAnimatable animatable, float partialTick) {
        if (animatable != null) {
            ServerGeoModelManager.getInstance().updateAnimation(
                    modelLocation,
                    animatable,
                    partialTick
            );
        }
    }

    /**
     * 静态工厂方法：创建关联
     */
    public static EntityCollection create(Entity entity, ResourceLocation modelLocation) {
        AnimationProcessor<GeoAnimatable> processor =
                ServerGeoModelManager.getInstance().getOrCreateAnimationProcessor(modelLocation);

        return new EntityCollection(
                entity.getUUID(),
                modelLocation,
                processor
        );
    }
}