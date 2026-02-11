package com.catoxide.catoxidesbattlerebuild.client.model;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * ModularZombie 的 GeckoLib 模型类
 * 负责加载和管理 ModularZombie 的 3D 模型
 */
public class ModularZombieModel extends GeoModel<ModularZombie> {

    /**
     * 获取模型资源位置
     */
    @Override
    public ResourceLocation getModelResource(ModularZombie animatable) {
        return new ResourceLocation(CatoxidesBattleRebuild.MODID, "geo/modular_zombie.geo.json");
    }

    /**
     * 获取纹理资源位置
     */
    @Override
    public ResourceLocation getTextureResource(ModularZombie animatable) {
        return new ResourceLocation(CatoxidesBattleRebuild.MODID, "textures/entity/modular_zombie.png");
    }

    /**
     * 获取动画资源位置
     */
    @Override
    public ResourceLocation getAnimationResource(ModularZombie animatable) {
        return new ResourceLocation(CatoxidesBattleRebuild.MODID, "animations/modular_zombie.animation.json");
    }
}


