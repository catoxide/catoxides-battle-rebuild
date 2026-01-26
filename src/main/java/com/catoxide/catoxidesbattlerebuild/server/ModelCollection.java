package com.catoxide.catoxidesbattlerebuild.server;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.model.CoreGeoModel;
import software.bernie.geckolib.core.animation.AnimationProcessor;

public record ModelCollection(
        CoreGeoModel coreModel,
        BakedGeoModel bakedModel,
        AnimationProcessor animationProcessor
) {}