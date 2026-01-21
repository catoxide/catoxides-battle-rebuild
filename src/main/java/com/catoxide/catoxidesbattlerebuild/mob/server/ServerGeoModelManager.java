package com.catoxide.catoxidesbattlerebuild.mob.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.model.CoreGeoModel;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.loading.FileLoader;
import software.bernie.geckolib.loading.json.raw.Model;
import software.bernie.geckolib.loading.object.BakedModelFactory;
import software.bernie.geckolib.loading.object.GeometryTree;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;


public class ServerGeoModelManager {

    public record ModelCollection(
            CoreGeoModel coreModel,
            BakedGeoModel bakedModel,
            AnimationProcessor processorTemplate
    ) {}

    private static final ServerGeoModelManager INSTANCE = new ServerGeoModelManager();
    private Map<ResourceLocation,ModelCollection> modelShelf = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, AnimationProcessor> animationProcessors = new HashMap<>();
    private boolean initialized = false;

    private ServerGeoModelManager() {}

    public static ServerGeoModelManager getInstance() {
        return INSTANCE;
    }

    //初始化管理器
    public void initialize(ResourceManager resourceManager, Executor executor) {
        if (initialized) return;

        loadModels(executor, resourceManager, this::addModel).join();

        // 将 ModelShelf 设为不可修改
        this.modelShelf = Collections.unmodifiableMap(this.modelShelf);
        this.initialized = true;

        GeckoLib.LOGGER.info("ServerGeoModelManager initialized with " + modelShelf.size() + " models");
    }

    private void addModel(ResourceLocation resource, ServerCoreGeoModel<GeoAnimatable> coreModel,
                          BakedGeoModel bakedModel, AnimationProcessor processorTemplate) {
        ModelCollection collection = new ModelCollection(coreModel, bakedModel, processorTemplate);
        modelShelf.put(resource, collection);
    }

    //从文件加载模型内容
    private CompletableFuture<Void> loadModels(Executor executor, ResourceManager resourceManager,
                                               ModelConsumer modelConsumer){
        return CompletableFuture.supplyAsync(
                        () -> resourceManager.listResources("geo", fileName -> fileName.toString().endsWith(".json")), executor)
                .thenComposeAsync(resources -> {
                    List<CompletableFuture<Void>> loadFutures = new ArrayList<>();

                    for (ResourceLocation resource : resources.keySet()) {
                        CompletableFuture<Void> loadFuture = CompletableFuture.runAsync(() ->{
                            try {
                                ServerCoreGeoModel<GeoAnimatable> coreModel = new ServerCoreGeoModel<>(resource);
                                Model model = FileLoader.loadModelFile(resource, resourceManager);
                                BakedGeoModel bakedModel = BakedModelFactory.getForNamespace(resource.getNamespace()) .constructGeoModel(GeometryTree.fromModel(model));
                                AnimationProcessor processorTemplate = new AnimationProcessor(coreModel);
                                ModelCollection collection = new ModelCollection(coreModel,bakedModel, processorTemplate);
                                modelConsumer.accept(resource, coreModel, bakedModel, processorTemplate);
                            } catch (Exception e) {
                                GeckoLib.LOGGER.error("Error loading model " + resource, e);
                                throw new RuntimeException("Failed to create CoreGeoModel", e);
                            }
                        }, executor);
                        loadFutures.add(loadFuture);
                    }
                    return CompletableFuture.allOf(loadFutures.toArray(new CompletableFuture[0]));
                });
    }
    @FunctionalInterface
    private interface ModelConsumer {
        void accept(ResourceLocation resource, ServerCoreGeoModel<GeoAnimatable> coreModel,
                    BakedGeoModel bakedModel, AnimationProcessor processorTemplate);
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

    //获取或创建动画处理器
    public AnimationProcessor<GeoAnimatable> getOrCreateAnimationProcessor(ResourceLocation modelLocation) {
        ensureInitialized();
        return animationProcessors.computeIfAbsent(modelLocation, location -> {
            ModelCollection collection = modelShelf.get(location);
            if (collection == null) {
                throw new IllegalArgumentException("Model not found: " + location);
            }

            // 使用模板创建新的动画处理器
            AnimationProcessor<GeoAnimatable> processor = new AnimationProcessor<>(collection.coreModel());
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
    public void updateAnimation(ResourceLocation modelLocation, GeoAnimatable animatable, float partialTick) {
        AnimationProcessor<GeoAnimatable> processor = getOrCreateAnimationProcessor(modelLocation);
        long uniqueId = getUniqueIdForAnimatable(animatable);
        AnimatableManager<GeoAnimatable> animatableManager = animatable.getAnimatableInstanceCache().getManagerForId(uniqueId);
        BakedGeoModel bakedModel = getBakedModel(modelLocation);
        CoreGeoModel coreModel = getCoreModel(modelLocation);
        double animTime = System.currentTimeMillis() / 50.0;
        float limbSwing = 0.0F;
        float limbSwingAmount = 0.0F;
        boolean isMoving = false;

        // 对于Entity类型，从Entity对象中获取这些值
        if (animatable instanceof net.minecraft.world.entity.Entity entity) {
            // 注意：不同版本的Minecraft可能有不同的方法名
            // 这里使用通用的方法名，实际使用时可能需要根据Minecraft版本调整
            if (entity instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
                limbSwing = livingEntity.walkAnimation.position();
                limbSwingAmount = livingEntity.walkAnimation.speed();
                isMoving = livingEntity.walkAnimation.isMoving();
            } else {
                // 对于非LivingEntity类型的Entity，使用默认值
                isMoving = entity.getDeltaMovement().lengthSqr() > 0.001D;
            }
        }

        AnimationState<GeoAnimatable> animationState = new AnimationState<>(animatable, 0, 0, partialTick, isMoving);
            processor.tickAnimation(animatable,coreModel, animatableManager, animTime, animationState, false);

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

}
