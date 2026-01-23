package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.loading.FileLoader;
import software.bernie.geckolib.loading.json.raw.Model;
import software.bernie.geckolib.loading.object.BakedModelFactory;
import software.bernie.geckolib.loading.object.GeometryTree;
import software.bernie.geckolib.core.animation.AnimationProcessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工厂类，负责创建和组装ModelCollection
 * 避免ServerGeoModelManager和ServerCoreGeoModel之间的循环依赖
 */
public class ModelCollectionFactory {

    private final Map<ResourceLocation, ServerGeoModelManager.ModelCollection> cache = new ConcurrentHashMap<>();

    /**
     * 创建完整的ModelCollection
     * 按照正确的依赖顺序创建所有组件
     */
    public ServerGeoModelManager.ModelCollection createModelCollection(
            ResourceLocation modelLocation,
            ResourceManager resourceManager
    ) {
        // 检查缓存
        if (cache.containsKey(modelLocation)) {
            return cache.get(modelLocation);
        }

        try {
            Model rawModel = loadRawModel(modelLocation, resourceManager);
            BakedGeoModel bakedModel = createBakedGeoModel(modelLocation, rawModel);
            ServerCoreGeoModel<GeoAnimatable> coreModel = new ServerCoreGeoModel<>(modelLocation,bakedModel);
            AnimationProcessor processorTemplate = createAnimationProcessor(coreModel, bakedModel);
            ServerGeoModelManager.ModelCollection collection =
                    new ServerGeoModelManager.ModelCollection(coreModel, bakedModel, processorTemplate);

            // 缓存结果
            cache.put(modelLocation, collection);

            GeckoLib.LOGGER.debug("Factory created ModelCollection for: {}", modelLocation);
            return collection;

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Factory failed to create ModelCollection for: {}", modelLocation, e);
            throw new RuntimeException("Model creation failed: " + modelLocation, e);
        }
    }

    /**
     * 步骤1：加载原始模型文件
     */
    private Model loadRawModel(ResourceLocation modelLocation, ResourceManager resourceManager) {
        try {
            Model rawModel = FileLoader.loadModelFile(modelLocation, resourceManager);
            if (rawModel == null) {
                throw new IllegalArgumentException("Failed to load raw model: " + modelLocation);
            }
            return rawModel;
        } catch (Exception e) {
            throw new RuntimeException("Error loading raw model: " + modelLocation, e);
        }
    }

    /**
     * 步骤2：创建BakedGeoModel
     */
    private BakedGeoModel createBakedGeoModel(ResourceLocation modelLocation, Model rawModel) {
        try {
            GeometryTree geometryTree = GeometryTree.fromModel(rawModel);
            BakedGeoModel bakedModel = BakedModelFactory.getForNamespace(modelLocation.getNamespace())
                    .constructGeoModel(geometryTree);

            if (bakedModel == null) {
                throw new IllegalArgumentException("Failed to create baked model: " + modelLocation);
            }
            return bakedModel;
        } catch (Exception e) {
            throw new RuntimeException("Error creating baked model: " + modelLocation, e);
        }
    }

    /**
     * 步骤4：创建AnimationProcessor模板
     */
    private AnimationProcessor createAnimationProcessor(
            ServerCoreGeoModel<GeoAnimatable> coreModel,
            BakedGeoModel bakedModel
    ) {
        try {
            AnimationProcessor processor = new AnimationProcessor(coreModel);
            processor.setActiveModel(bakedModel);
            return processor;
        } catch (Exception e) {
            throw new RuntimeException("Error creating animation processor", e);
        }
    }

    /**
     * 批量创建模型集合
     */
    public Map<ResourceLocation, ServerGeoModelManager.ModelCollection> createModelCollections(
            Map<ResourceLocation, ResourceManager> resources,
            ResourceManager resourceManager
    ) {
        Map<ResourceLocation, ServerGeoModelManager.ModelCollection> collections = new ConcurrentHashMap<>();

        for (ResourceLocation resource : resources.keySet()) {
            try {
                ServerGeoModelManager.ModelCollection collection =
                        createModelCollection(resource, resourceManager);
                collections.put(resource, collection);
            } catch (Exception e) {
                GeckoLib.LOGGER.error("Failed to create model for: {}", resource, e);
            }
        }

        return collections;
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        cache.clear();
        GeckoLib.LOGGER.debug("ModelCollectionFactory cache cleared");
    }
}