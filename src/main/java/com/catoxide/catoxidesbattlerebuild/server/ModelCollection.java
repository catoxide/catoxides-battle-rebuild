package com.catoxide.catoxidesbattlerebuild.server;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.model.CoreGeoModel;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.loading.object.BakedAnimations;

import java.util.Map;

public record ModelCollection(
        CoreGeoModel coreModel,
        BakedGeoModel bakedModel,
        BakedAnimations bakedAnimations,
        AnimationProcessor animationProcessor
) {}