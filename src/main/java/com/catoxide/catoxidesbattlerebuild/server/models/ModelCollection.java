package com.catoxide.catoxidesbattlerebuild.server.models;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.model.CoreGeoModel;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.loading.object.BakedAnimations;

public record ModelCollection(
        CoreGeoModel coreModel,
        BakedGeoModel bakedModel,
        BakedAnimations bakedAnimations,
        AnimationProcessor animationProcessor
) {}