package com.catoxide.catoxidesbattlerebuild.client.model;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class ModularZombieModel extends GeoModel<ModularZombie> {

    @Override
    public ResourceLocation getModelResource(ModularZombie animatable) {
        return ResourceLocation.fromNamespaceAndPath(CatoxidesBattleRebuild.MODID, "geo/modular_zombie.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ModularZombie animatable) {
        return ResourceLocation.fromNamespaceAndPath(CatoxidesBattleRebuild.MODID, "textures/entity/modular_zombie.png");
    }

    @Override
    public ResourceLocation getAnimationResource(ModularZombie animatable) {
        return ResourceLocation.fromNamespaceAndPath(CatoxidesBattleRebuild.MODID, "animations/modular_zombie.animation.json");
    }
}