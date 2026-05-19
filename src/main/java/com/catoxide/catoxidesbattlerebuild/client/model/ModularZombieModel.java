package com.catoxide.catoxidesbattlerebuild.client.model;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class ModularZombieModel extends GeoModel<ModularZombie> {
    @Override
    public ResourceLocation getModelResource(ModularZombie animatable) {
        return ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "geo/modular_zombie.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ModularZombie animatable) {
        return ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "textures/entity/modular_zombie.png");
    }

    @Override
    public ResourceLocation getAnimationResource(ModularZombie animatable) {
        return ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "animations/modular_zombie.animation.json");
    }
}