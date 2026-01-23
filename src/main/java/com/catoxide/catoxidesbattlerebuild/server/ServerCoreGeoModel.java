package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.model.CoreGeoModel;
import software.bernie.geckolib.core.animatable.model.CoreBakedGeoModel;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.core.animation.AnimationState;

/**
 * Server-side implementation of CoreGeoModel that can work with any GeoAnimatable type.
 * This allows creating GeoModel instances for unspecified models on the server side.
 */
public class ServerCoreGeoModel<E extends GeoAnimatable> implements CoreGeoModel<E> {
    private final ResourceLocation modelLocation;
    private final AnimationProcessor<E> animationProcessor;
    private final BakedGeoModel bakedModel;
    /**
     * Creates a new ServerCoreGeoModel instance for the given model location.
     * @param modelLocation The resource location of the model
     */
    public ServerCoreGeoModel(ResourceLocation modelLocation, BakedGeoModel bakedModel) {
        this.modelLocation = modelLocation;
        this.bakedModel = bakedModel;
        this.animationProcessor = new AnimationProcessor<>(this);

        // 直接使用传入的bakedModel，不依赖Manager
        if (bakedModel != null) {
            animationProcessor.setActiveModel(bakedModel);
        } else {
            GeckoLib.LOGGER.warn("BakedModel is null for: {}", modelLocation);
        }
    }

    /**
     * Gets the baked model data for this model based on the provided string location.
     * @param location The resource path of the baked model
     * @return The CoreBakedGeoModel
     */
    @Override
    public CoreBakedGeoModel getBakedGeoModel(String location) {
        ResourceLocation loc = ResourceLocation.tryParse(location);
        if (loc == null) {
            loc = modelLocation;
        }
        if (loc.equals(modelLocation) && bakedModel != null) {
            return bakedModel;
        }
        return ServerGeoModelManager.getInstance().getBakedModel(loc);
    }

    /**
     * Gets the AnimationProcessor for this model.
     * @return The AnimationProcessor
     */
    @Override
    public AnimationProcessor<E> getAnimationProcessor() {
        return animationProcessor;
    }

    /**
     * Gets the loaded Animation for the given animation name, if it exists.
     * @param animatable The GeoAnimatable instance being referred to
     * @param name The name of the animation to retrieve
     * @return The Animation instance for the provided name, or null if none match
     */
    @Override
    public Animation getAnimation(E animatable, String name) {
        // In a real implementation, you would load animations from files
        // For this simple implementation, we'll return null
        return null;
    }

    /**
     * This method is called once per render frame for each GeoAnimatable being rendered.
     * It is an internal method for automated animation parsing.
     * @param animatable The GeoAnimatable instance currently being rendered
     * @param instanceId The instance id of the GeoAnimatable
     * @param animationState An AnimationState instance created to hold animation data
     */
    @Override
    public void handleAnimations(E animatable, long instanceId, AnimationState<E> animationState) {
        // Update the animation using the ServerGeoModelManager
        ServerGeoModelManager.getInstance().updateAnimation(modelLocation, animatable, animationState.getPartialTick());
    }

    /**
     * Gets the model location for this ServerCoreGeoModel.
     * @return The model location
     */
    public ResourceLocation getModelLocation() {
        return modelLocation;
    }
}