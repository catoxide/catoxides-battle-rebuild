package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.model.CoreGeoModel;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.core.animation.AnimationState;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;


public class ServerGeoModelManager {

    private static final ServerGeoModelManager INSTANCE = new ServerGeoModelManager();
    private Map<ResourceLocation,ModelCollection> modelShelf = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, AnimationProcessor> animationProcessors = new ConcurrentHashMap<>();
    private boolean initialized = false;
    private final ModelCollectionFactory factory = new ModelCollectionFactory();

    private ServerGeoModelManager() {}

    public static ServerGeoModelManager getInstance() {
        return INSTANCE;
    }

    //初始化管理器
    public void initialize(ResourceManager resourceManager, Executor executor) {
        if (initialized) return;
        loadModels(executor, resourceManager).join();
        // 将 ModelShelf 设为不可修改
        this.modelShelf = Collections.unmodifiableMap(this.modelShelf);
        this.initialized = true;
        GeckoLib.LOGGER.info("ServerGeoModelManager initialized with " + modelShelf.size() + " models");
    }

    //从文件加载模型内容
    private CompletableFuture<Void> loadModels(Executor executor, ResourceManager resourceManager) {
        return CompletableFuture.supplyAsync(
                () -> resourceManager.listResources("geo", fileName -> fileName.toString().endsWith(".json")),
                executor
        ).thenComposeAsync(resources -> {
            List<CompletableFuture<Void>> loadFutures = new ArrayList<>();

            for (ResourceLocation resource : resources.keySet()) {
                CompletableFuture<Void> loadFuture = CompletableFuture.runAsync(() -> {
                    try {
                        // 关键：使用工厂创建完整的ModelCollection
                        ModelCollection collection =
                                factory.createModelCollection(resource, resourceManager);

                        // 直接存储到modelShelf
                        modelShelf.put(resource, collection);

                        GeckoLib.LOGGER.debug("Successfully loaded model: {}", resource);
                    } catch (Exception e) {
                        GeckoLib.LOGGER.error("Factory failed to create model for: {}", resource, e);
                    }
                }, executor);

                loadFutures.add(loadFuture);
            }

            return CompletableFuture.allOf(loadFutures.toArray(new CompletableFuture[0]));
        });
    }
    public void clearCache() {
        factory.clearCache();
        modelShelf.clear();
        animationProcessors.clear();
        initialized = false;
        GeckoLib.LOGGER.info("ServerGeoModelManager cache cleared");
    }
    //获取Baked模型
    public BakedGeoModel getBakedModel(ResourceLocation modelLocation) {
        ensureInitialized();
        ModelCollection collection = modelShelf.get(modelLocation);
        return collection != null ? collection.bakedModel() : null;
    }
    public CoreGeoModel getCoreModel(ResourceLocation modelLocation) {
        ensureInitialized();
        ModelCollection collection = modelShelf.get(modelLocation);
        return collection != null ? collection.coreModel() : null;
    }
    public ModelCollection getModelCollection(ResourceLocation modelLocation) {
        ensureInitialized();
        return modelShelf.get(modelLocation);
    }
    //获取或创建动画处理器
    public AnimationProcessor<GeoAnimatable> getOrCreateAnimationProcessor(ResourceLocation modelLocation) {
        ensureInitialized();
        return animationProcessors.computeIfAbsent(modelLocation, location -> {
            ModelCollection collection = modelShelf.get(location);
            if (collection == null) {
                throw new IllegalArgumentException("Model not found: " + location);
            }

            // 使用模板创建新的动画处理器
            AnimationProcessor<GeoAnimatable> processor = collection.animationProcessor();
            processor.setActiveModel(collection.bakedModel());
            return processor;
        });
    }

    //确保模型初始化
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("ServerGeoModelManager not initialized");
        }
    }

    //更新动画
    private void updateAnimation(
            AnimationProcessor<GeoAnimatable> processor,
            ResourceLocation modelLocation,
            GeoAnimatable animatable,
            float partialTick) {
        ensureInitialized();

        long uniqueId = getUniqueIdForAnimatable(animatable);
        AnimatableManager<GeoAnimatable> animatableManager =
                animatable.getAnimatableInstanceCache().getManagerForId(uniqueId);
        BakedGeoModel bakedModel = getBakedModel(modelLocation);
        CoreGeoModel coreModel = getCoreModel(modelLocation);

        if (bakedModel == null || coreModel == null) {
            GeckoLib.LOGGER.error("Model not found for location: {}", modelLocation);
            return;
        }

        double animTime = System.currentTimeMillis() / 50.0;
        float limbSwing = 0.0F;
        float limbSwingAmount = 0.0F;
        boolean isMoving = false;

        // 对于Entity类型，从Entity对象中获取这些值
        if (animatable instanceof net.minecraft.world.entity.Entity entity) {
            if (entity instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
                limbSwing = livingEntity.walkAnimation.position();
                limbSwingAmount = livingEntity.walkAnimation.speed();
                isMoving = livingEntity.walkAnimation.isMoving();
            } else {
                // 对于非LivingEntity类型的Entity，使用默认值
                isMoving = entity.getDeltaMovement().lengthSqr() > 0.001D;
            }
        }

        // Debug：输出动画更新信息
        System.out.println("[ServerGeoModelManager] Updating animation for entity: " + 
                (animatable instanceof net.minecraft.world.entity.Entity ? 
                        ((net.minecraft.world.entity.Entity)animatable).getUUID() : "unknown") +
                ", animTime: " + animTime +
                ", limbSwing: " + limbSwing +
                ", isMoving: " + isMoving);

        AnimationState<GeoAnimatable> animationState =
                new AnimationState<>(animatable, 0, 0, partialTick, isMoving);
        processor.tickAnimation(animatable, coreModel, animatableManager, animTime, animationState, false);
        
        System.out.println("[ServerGeoModelManager] Animation tick completed");
    }
    public void updateAnimation(EntityCollection collection, float partialTick) {
        if (!collection.isValid()) {
            GeckoLib.LOGGER.warn("Attempted to update animation for invalid entity: {}", collection.entityId());
            return;
        }

        // 直接从collection获取动画处理器
        AnimationProcessor<GeoAnimatable> processor = collection.animationProcessor();
        GeoAnimatable animatable = (GeoAnimatable) collection.entity(); // 已知entity是GeoAnimatable
        ResourceLocation modelLocation = collection.modelLocation();

        updateAnimation(processor, modelLocation, animatable, partialTick);
    }
    //更新动画 - 基于资源位置和实体
    public void updateAnimation(ResourceLocation modelLocation, GeoAnimatable animatable, float partialTick) {
        AnimationProcessor<GeoAnimatable> processor = getOrCreateAnimationProcessor(modelLocation);
        updateAnimation(processor, modelLocation, animatable, partialTick);
    }

private long getUniqueIdForAnimatable(GeoAnimatable animatable) {
    // 对于实体类型的GeoAnimatable，使用实体ID
    if (animatable instanceof net.minecraft.world.entity.Entity entity) {
        return entity.getId();
    }
    // 对于物品类型的GeoAnimatable，需要特殊处理
    // 注意：这里可能需要根据实际情况调整，因为物品通常需要通过ItemStack来获取ID
//    if (animatable instanceof software.bernie.geckolib.animatable.GeoItem geoItem) {
//        // 如果是在服务端，可能需要通过其他方式获取ItemStack
//        // 这里暂时返回一个默认值，实际使用时需要修改
//        return Long.MAX_VALUE;
//    }
    // 对于方块实体类型的GeoAnimatable
    if (animatable instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        net.minecraft.core.BlockPos pos = blockEntity.getBlockPos();
        // 使用方块实体的位置作为唯一ID
        return pos.asLong();
    }
    // 对于单例类型的GeoAnimatable
//    if (animatable instanceof software.bernie.geckolib.animatable.SingletonGeoAnimatable) {
//        // 单例对象通常使用0或其他固定ID
//        return 0;
//    }
    // 默认情况下，使用对象的哈希码作为唯一ID
    return System.identityHashCode(animatable);
}
    public Collection<ResourceLocation> getAllLoadedModelLocations() {
        ensureInitialized();
        return modelShelf.keySet();
    }

    public boolean isInitialized() {
        return initialized;
    }
}
