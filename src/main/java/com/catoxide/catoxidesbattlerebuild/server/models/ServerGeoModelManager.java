package com.catoxide.catoxidesbattlerebuild.server.models;

import com.catoxide.catoxidesbattlerebuild.server.geometry.EntityCollection;
import com.catoxide.catoxidesbattlerebuild.server.geometry.EntityCollectionFactory;
import com.catoxide.catoxidesbattlerebuild.server.geometry.ServerEntityManager;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.packs.resources.ResourceManager;
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
        LogManager.serverInfo("ServerGeoModelManager", "ServerGeoModelManager initialized with " + modelShelf.size() + " models");
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

                        LogManager.serverDebug("ServerGeoModelManager", "Successfully loaded model: {}", resource);
                    } catch (Exception e) {
                        LogManager.serverError("ServerGeoModelManager", "Factory failed to create model for: " + resource, e);
                    }
                }, executor);
                loadFutures.add(loadFuture);
            }

            return CompletableFuture.allOf(loadFutures.toArray(new CompletableFuture[0]));
        });
    }

    //清空缓存
    public void clearCache() {
        modelShelf.clear();
        animationProcessors.clear();
        initialized = false;
        LogManager.serverInfo("ServerGeoModelManager", "ServerGeoModelManager cache cleared");
    }

    //获取模型集合
    public ModelCollection getModelCollection(ResourceLocation modelLocation) {
        ModelCollection collection = modelShelf.get(modelLocation);
        if (collection == null) {
            LogManager.serverError("ServerGeoModelManager", "Model not found for location: " + modelLocation);
            throw new RuntimeException("Model not found: " + modelLocation);
        }
        return collection;
    }

    //获取模型
    public BakedGeoModel getModel(ResourceLocation modelLocation) {
        ModelCollection collection = getModelCollection(modelLocation);
        return collection.bakedModel();
    }

    //获取核心模型
    public CoreGeoModel getCoreModel(ResourceLocation modelLocation) {
        ModelCollection collection = getModelCollection(modelLocation);
        return collection.coreModel();
    }

    //获取或创建动画处理器
    public AnimationProcessor getOrCreateAnimationProcessor(ResourceLocation modelLocation) {
        return animationProcessors.computeIfAbsent(modelLocation, loc -> {
            CoreGeoModel model = getCoreModel(modelLocation);
            AnimationProcessor processor = new AnimationProcessor(model);
            LogManager.serverDebug("ServerGeoModelManager", "Created animation processor for: {}", loc);
            return processor;
        });
    }

    //获取动画处理器
    public AnimationProcessor getAnimationProcessor(ResourceLocation modelLocation) {
        return animationProcessors.get(modelLocation);
    }

    //更新动画 - 基于资源位置和实体
    public void updateAnimation(ResourceLocation modelLocation, GeoAnimatable animatable, float partialTick) {
        AnimationProcessor<GeoAnimatable> processor = getOrCreateAnimationProcessor(modelLocation);
        updateAnimation(processor, modelLocation, animatable, partialTick);
    }

    //更新动画 - 完整参数版本
    public void updateAnimation(AnimationProcessor<GeoAnimatable> processor, ResourceLocation modelLocation, 
                                GeoAnimatable animatable, float partialTick) {
        // 获取实体ID
        long entityId = -1;
        String entityUuid = "unknown";
        if (animatable instanceof net.minecraft.world.entity.Entity entity) {
            entityId = entity.getId();
            entityUuid = entity.getUUID().toString();
        }

        // 获取模型集合
        ModelCollection modelCollection = getModelCollection(modelLocation);
        
        // 执行动画tick
        try {
            // 获取骨骼矩阵
            Map<String, org.joml.Matrix4f> boneMatrices = getBoneMatricesFromProcessor(processor, modelLocation);
            
            // 添加实体位置到骨骼矩阵
            if (animatable instanceof net.minecraft.world.entity.Entity entity) {
                addEntityPositionToMatrices(boneMatrices, entity, 0.975f);
            }
            
            LogManager.serverInfo("ServerGeoModelManager", "updateAnimation for " + entityUuid + ": boneMatrices=" + boneMatrices.size());
            
        } catch (Exception e) {
            LogManager.serverError("ServerGeoModelManager", "Failed to update animation for entity " + entityId + ": " + e.getMessage(), e);
        }
    }

    /**
     * 将实体位置添加到骨骼矩阵
     */
    private void addEntityPositionToMatrices(Map<String, org.joml.Matrix4f> boneMatrices, 
                                              net.minecraft.world.entity.Entity entity, float modelOffsetY) {
        double entityX = entity.getX();
        double entityY = entity.getY();
        double entityZ = entity.getZ();
        
        // 添加Y轴偏移（模型原点偏移）
        entityY += modelOffsetY;
        
        LogManager.serverInfo("ServerGeoModelManager", 
            "Added entity position ({}, {}, {}) with offset {} to bone matrices", 
            entityX, entityY, entityZ, modelOffsetY);
        
        // 将实体位置应用到每个骨骼矩阵
        for (Map.Entry<String, org.joml.Matrix4f> entry : boneMatrices.entrySet()) {
            org.joml.Matrix4f matrix = entry.getValue();
            
            // 创建平移矩阵
            org.joml.Matrix4f translationMatrix = new org.joml.Matrix4f()
                .translate((float) entityX, (float) entityY, (float) entityZ);
            
            // 将骨骼矩阵与平移矩阵相乘
            matrix.mul(translationMatrix);
            
            entry.setValue(matrix);
        }
    }

    /**
     * 从AnimationProcessor获取骨骼矩阵
     */
    public Map<String, org.joml.Matrix4f> getBoneMatricesFromProcessor(AnimationProcessor<GeoAnimatable> processor, 
                                                                         ResourceLocation modelLocation) {
        Map<String, org.joml.Matrix4f> boneMatrices = new ConcurrentHashMap<>();
        
        try {
            // 使用反射获取AnimationProcessor的bones字段
            java.lang.reflect.Field bonesField = AnimationProcessor.class.getDeclaredField("bones");
            bonesField.setAccessible(true);
            Map<String, software.bernie.geckolib.cache.object.GeoBone> bones = 
                (Map<String, software.bernie.geckolib.cache.object.GeoBone>) bonesField.get(processor);
            
            if (bones != null && !bones.isEmpty()) {
                // 遍历所有骨骼，获取世界空间矩阵
                for (Map.Entry<String, software.bernie.geckolib.cache.object.GeoBone> entry : bones.entrySet()) {
                    String boneName = entry.getKey();
                    software.bernie.geckolib.cache.object.GeoBone bone = entry.getValue();
                    
                    // 尝试获取骨骼矩阵
                    org.joml.Matrix4f boneMatrix = buildBoneMatrixManually(bone, boneMatrices, boneName);
                    
                    if (boneMatrix != null) {
                        boneMatrices.put(boneName, boneMatrix);
                    }
                }
                
                LogManager.serverInfo("ServerGeoModelManager", "getBoneMatricesFromProcessor: found {} bones with matrices", 
                    boneMatrices.size());
            } else {
                // 如果AnimationProcessor没有骨骼数据，从模型数据中构建静态骨骼矩阵
                LogManager.serverWarn("ServerGeoModelManager", "No bones found in processor, using static bone data");
                boneMatrices = buildStaticBoneMatrices(modelLocation);
            }
            
        } catch (NoSuchFieldException e) {
            LogManager.serverError("ServerGeoModelManager", "Failed to get bone matrices from processor: " + e.getMessage(), e);
            // 使用静态骨骼数据作为后备
            boneMatrices = buildStaticBoneMatrices(modelLocation);
        } catch (IllegalAccessException e) {
            LogManager.serverError("ServerGeoModelManager", "Failed to access bones field: " + e.getMessage(), e);
            // 使用静态骨骼数据作为后备
            boneMatrices = buildStaticBoneMatrices(modelLocation);
        }
        
        return boneMatrices;
    }
    
    /**
     * 从模型数据构建静态骨骼矩阵
     */
    private Map<String, org.joml.Matrix4f> buildStaticBoneMatrices(ResourceLocation modelLocation) {
        Map<String, org.joml.Matrix4f> boneMatrices = new ConcurrentHashMap<>();
        
        try {
            // 尝试从模型集合获取骨骼数据
            ModelCollection collection = getModelCollection(modelLocation);
            if (collection == null) {
                LogManager.serverWarn("ServerGeoModelManager", "Model collection not found for: {}", modelLocation);
                return boneMatrices;
            }
            
            // 方法1：尝试从GeoModelData获取（通过反射）
            boneMatrices = buildStaticBoneMatricesFromModel(collection);
            
            // 如果方法1失败，尝试方法2：直接读取Geo JSON文件
            if (boneMatrices.isEmpty()) {
                boneMatrices = buildStaticBoneMatricesFromJson(modelLocation);
            }
            
            LogManager.serverInfo("ServerGeoModelManager", "buildStaticBoneMatrices: built {} static bone matrices for {}", 
                boneMatrices.size(), modelLocation);
                
        } catch (Exception e) {
            LogManager.serverError("ServerGeoModelManager", "Failed to build static bone matrices: " + e.getMessage(), e);
        }
        
        return boneMatrices;
    }
    
    /**
     * 从CoreGeoModel获取静态骨骼矩阵
     */
    private Map<String, org.joml.Matrix4f> buildStaticBoneMatricesFromModel(ModelCollection collection) {
        Map<String, org.joml.Matrix4f> boneMatrices = new ConcurrentHashMap<>();
        
        try {
            CoreGeoModel coreModel = collection.coreModel();
            if (coreModel == null) {
                return boneMatrices;
            }
            
            // 尝试获取GeoModelData
            java.lang.reflect.Field modelDataField = CoreGeoModel.class.getDeclaredField("modelData");
            modelDataField.setAccessible(true);
            Object modelData = modelDataField.get(coreModel);
            
            if (modelData != null) {
                // 获取GeometryTree
                java.lang.reflect.Method getGeometryTreeMethod = modelData.getClass().getDeclaredMethod("getGeometryTree");
                getGeometryTreeMethod.setAccessible(true);
                Object geometryTree = getGeometryTreeMethod.invoke(modelData);
                
                if (geometryTree != null) {
                    // 获取topLevelBones方法
                    java.lang.reflect.Method topLevelBonesMethod = geometryTree.getClass().getDeclaredMethod("topLevelBones");
                    topLevelBonesMethod.setAccessible(true);
                    Map<String, Object> topLevelBones = (Map<String, Object>) topLevelBonesMethod.invoke(geometryTree);
                    
                    if (topLevelBones != null) {
                        // 递归处理骨骼
                        for (Map.Entry<String, Object> entry : topLevelBones.entrySet()) {
                            buildStaticBoneMatrixRecursive(entry.getValue(), boneMatrices, null, new org.joml.Matrix4f());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogManager.serverDebug("ServerGeoModelManager", "Failed to build bone matrices from model: " + e.getMessage());
        }
        
        return boneMatrices;
    }
    
    /**
     * 从Geo JSON文件直接读取静态骨骼矩阵
     */
    private Map<String, org.joml.Matrix4f> buildStaticBoneMatricesFromJson(ResourceLocation modelLocation) {
        Map<String, org.joml.Matrix4f> boneMatrices = new ConcurrentHashMap<>();
        
        try {
            // 构建Geo JSON文件路径
            String geoPath = "geo/" + modelLocation.getPath() + ".geo.json";
            ResourceLocation geoResource = new ResourceLocation(modelLocation.getNamespace(), geoPath);
            
            // 获取资源管理器 - 使用正确的API
            net.minecraft.server.packs.resources.ResourceManager resourceManager = 
                net.minecraft.server.MinecraftServer.getServer().getResourceManager();
            
            // 使用try-catch处理资源获取
            java.util.Optional<net.minecraft.server.packs.resources.Resource> resource = resourceManager.getResource(geoResource);
            if (resource.isPresent()) {
                try (java.io.InputStream inputStream = resource.get().open()) {
                    // 使用GeckoLib的JsonUtil解析
                    software.bernie.geckolib.loading.object.GeometryTree geometryTree = 
                        software.bernie.geckolib.util.JsonUtil.GEO_GSON.fromJson(
                            new java.io.InputStreamReader(inputStream), 
                            software.bernie.geckolib.loading.object.GeometryTree.class);
                    
                    if (geometryTree != null) {
                        Map<String, software.bernie.geckolib.loading.object.BoneStructure> topLevelBones = 
                            geometryTree.topLevelBones();
                        
                        if (topLevelBones != null) {
                            // 递归处理骨骼
                            for (Map.Entry<String, software.bernie.geckolib.loading.object.BoneStructure> entry : topLevelBones.entrySet()) {
                                buildStaticBoneMatrixFromStructure(entry.getValue(), boneMatrices, null, new org.joml.Matrix4f());
                            }
                        }
                    }
                }
            } else {
                LogManager.serverWarn("ServerGeoModelManager", "Geo JSON file not found: {}", geoResource);
            }
        } catch (Exception e) {
            LogManager.serverError("ServerGeoModelManager", "Failed to build bone matrices from JSON: " + e.getMessage(), e);
        }
        
        return boneMatrices;
    }
    
    /**
     * 从BoneStructure递归构建静态骨骼矩阵
     */
    private void buildStaticBoneMatrixFromStructure(
            software.bernie.geckolib.loading.object.BoneStructure boneStructure,
            Map<String, org.joml.Matrix4f> boneMatrices,
            String parentName,
            org.joml.Matrix4f parentMatrix) {
        
        try {
            // 获取Bone对象
            software.bernie.geckolib.loading.json.raw.Bone bone = boneStructure.self();
            String boneName = bone.name();
            
            // 获取pivot
            double[] pivot = bone.pivot() != null ? bone.pivot() : new double[]{0, 0, 0};
            
            // 获取rotation
            double[] rotation = bone.rotation() != null ? bone.rotation() : new double[]{0, 0, 0};
            
            // 获取scale - 使用try-catch处理，因为scale方法可能不存在
            double[] scale = new double[]{1, 1, 1};
            try {
                // 尝试反射获取scale
                java.lang.reflect.Method scaleMethod = bone.getClass().getDeclaredMethod("scale");
                scaleMethod.setAccessible(true);
                Object scaleObj = scaleMethod.invoke(bone);
                if (scaleObj instanceof double[]) {
                    scale = (double[]) scaleObj;
                }
            } catch (Exception ignored) {
                // scale方法不存在，使用默认值
            }
            
            // 构建骨骼矩阵
            org.joml.Matrix4f boneMatrix = new org.joml.Matrix4f(parentMatrix);
            
            // 应用旋转
            float rotX = (float) Math.toRadians(rotation[0]);
            float rotY = (float) Math.toRadians(rotation[1]);
            float rotZ = (float) Math.toRadians(rotation[2]);
            boneMatrix.rotateXYZ(rotX, rotY, rotZ);
            
            // 应用平移（pivot）- Minecraft单位转换
            boneMatrix.translate((float) pivot[0] / 16.0f, (float) pivot[1] / 16.0f, (float) pivot[2] / 16.0f);
            
            // 应用缩放
            boneMatrix.scale((float) scale[0], (float) scale[1], (float) scale[2]);
            
            // 存储骨骼矩阵
            boneMatrices.put(boneName, boneMatrix);
            
            // 递归处理子骨骼
            Map<String, software.bernie.geckolib.loading.object.BoneStructure> children = boneStructure.children();
            if (children != null && !children.isEmpty()) {
                for (Map.Entry<String, software.bernie.geckolib.loading.object.BoneStructure> entry : children.entrySet()) {
                    buildStaticBoneMatrixFromStructure(entry.getValue(), boneMatrices, boneName, boneMatrix);
                }
            }
            
        } catch (Exception e) {
            LogManager.serverError("ServerGeoModelManager", "Failed to build static bone matrix from structure: " + e.getMessage(), e);
        }
    }
    
    /**
     * 递归构建静态骨骼矩阵
     */
    private void buildStaticBoneMatrixRecursive(Object boneStructure, Map<String, org.joml.Matrix4f> boneMatrices,
                                                String parentName, org.joml.Matrix4f parentMatrix) {
        try {
            // 获取self方法获取Bone对象
            java.lang.reflect.Method selfMethod = boneStructure.getClass().getDeclaredMethod("self");
            selfMethod.setAccessible(true);
            Object bone = selfMethod.invoke(boneStructure);
            
            // 获取骨骼名称
            java.lang.reflect.Method nameMethod = bone.getClass().getDeclaredMethod("name");
            nameMethod.setAccessible(true);
            String boneName = (String) nameMethod.invoke(bone);
            
            // 获取pivot
            double[] pivot = new double[]{0, 0, 0};
            try {
                java.lang.reflect.Method pivotMethod = bone.getClass().getDeclaredMethod("pivot");
                pivotMethod.setAccessible(true);
                pivot = (double[]) pivotMethod.invoke(bone);
            } catch (Exception ignored) {}
            
            // 获取rotation
            double[] rotation = new double[]{0, 0, 0};
            try {
                java.lang.reflect.Method rotationMethod = bone.getClass().getDeclaredMethod("rotation");
                rotationMethod.setAccessible(true);
                rotation = (double[]) rotationMethod.invoke(bone);
            } catch (Exception ignored) {}
            
            // 获取scale
            double[] scale = new double[]{1, 1, 1};
            try {
                java.lang.reflect.Method scaleMethod = bone.getClass().getDeclaredMethod("scale");
                scaleMethod.setAccessible(true);
                scale = (double[]) scaleMethod.invoke(bone);
            } catch (Exception ignored) {}
            
            // 构建骨骼矩阵
            org.joml.Matrix4f boneMatrix = new org.joml.Matrix4f(parentMatrix);
            
            // 应用旋转
            if (rotation != null) {
                float rotX = (float) Math.toRadians(rotation[0]);
                float rotY = (float) Math.toRadians(rotation[1]);
                float rotZ = (float) Math.toRadians(rotation[2]);
                boneMatrix.rotateXYZ(rotX, rotY, rotZ);
            }
            
            // 应用平移（pivot）
            if (pivot != null) {
                boneMatrix.translate((float) pivot[0] / 16.0f, (float) pivot[1] / 16.0f, (float) pivot[2] / 16.0f);
            }
            
            // 应用缩放
            if (scale != null) {
                boneMatrix.scale((float) scale[0], (float) scale[1], (float) scale[2]);
            }
            
            // 存储骨骼矩阵
            boneMatrices.put(boneName, boneMatrix);
            
            // 递归处理子骨骼
            try {
                java.lang.reflect.Method childrenMethod = boneStructure.getClass().getDeclaredMethod("children");
                childrenMethod.setAccessible(true);
                Map<String, Object> children = (Map<String, Object>) childrenMethod.invoke(boneStructure);
                
                if (children != null && !children.isEmpty()) {
                    for (Map.Entry<String, Object> childEntry : children.entrySet()) {
                        buildStaticBoneMatrixRecursive(childEntry.getValue(), boneMatrices, boneName, boneMatrix);
                    }
                }
            } catch (Exception ignored) {}
            
        } catch (Exception e) {
            LogManager.serverError("ServerGeoModelManager", "Failed to build static bone matrix recursively: " + e.getMessage(), e);
        }
    }

    /**
     * 手动构建骨骼矩阵
     */
    private org.joml.Matrix4f buildBoneMatrixManually(software.bernie.geckolib.cache.object.GeoBone bone, 
                                                       Map<String, org.joml.Matrix4f> boneMatrices, 
                                                       String boneName) {
        try {
            // 获取骨骼的pivot位置
            float pivotX = bone.getPivotX();
            float pivotY = bone.getPivotY();
            float pivotZ = bone.getPivotZ();
            
            // 获取骨骼的旋转
            float rotationX = 0, rotationY = 0, rotationZ = 0;
            
            try {
                java.lang.reflect.Field rotXField = software.bernie.geckolib.cache.object.GeoBone.class.getDeclaredField("rotationX");
                java.lang.reflect.Field rotYField = software.bernie.geckolib.cache.object.GeoBone.class.getDeclaredField("rotationY");
                java.lang.reflect.Field rotZField = software.bernie.geckolib.cache.object.GeoBone.class.getDeclaredField("rotationZ");
                
                rotXField.setAccessible(true);
                rotYField.setAccessible(true);
                rotZField.setAccessible(true);
                
                rotationX = rotXField.getFloat(bone);
                rotationY = rotYField.getFloat(bone);
                rotationZ = rotZField.getFloat(bone);
                
                LogManager.serverDebug("ServerGeoModelManager", "Got rotation for bone '{}': ({}, {}, {})", 
                    boneName, rotationX, rotationY, rotationZ);
            } catch (NoSuchFieldException e) {
                LogManager.serverWarn("ServerGeoModelManager", "Could not get rotation for bone '{}': {}", boneName, e.getMessage());
            }
            
            // 获取骨骼的缩放
            float scaleX = bone.getScaleX();
            float scaleY = bone.getScaleY();
            float scaleZ = bone.getScaleZ();
            
            // 构建变换矩阵
            org.joml.Matrix4f matrix = new org.joml.Matrix4f();
            
            // 关键修复：JOML矩阵使用右乘，操作顺序是相反的
            // 所以需要先手动将pivot从像素坐标转换为方块坐标
            float blockPivotX = pivotX / 16.0f;
            float blockPivotY = pivotY / 16.0f;
            float blockPivotZ = pivotZ / 16.0f;
            
            // 第一步：应用骨骼自身的缩放
            matrix.scale(scaleX, scaleY, scaleZ);
            
            // 第二步：应用旋转（弧度）
            matrix.rotateX(rotationX);
            matrix.rotateY(rotationY);
            matrix.rotateZ(rotationZ);
            
            // 第三步：应用平移（使用方块坐标的pivot）
            matrix.translate(blockPivotX, blockPivotY, blockPivotZ);
            
            // 获取最终平移值用于调试
            org.joml.Vector3f translation = new org.joml.Vector3f();
            matrix.getTranslation(translation);
            
            LogManager.serverTransformDebug("ServerGeoModelManager", 
                "Bone '" + boneName + "' manually built matrix:" +
                "\n  - Pivot (pixels): (" + pivotX + ", " + pivotY + ", " + pivotZ + ")" +
                "\n  - Pivot (blocks): (" + blockPivotX + ", " + blockPivotY + ", " + blockPivotZ + ")" +
                "\n  - Rotation (rad): (" + rotationX + ", " + rotationY + ", " + rotationZ + ")" +
                "\n  - Bone scale (getScale): (" + scaleX + ", " + scaleY + ", " + scaleZ + ")" +
                "\n  - Final translation (blocks): (" + translation.x() + ", " + translation.y() + ", " + translation.z() + ")");
            
            // 调试：检查矩阵的缩放分量
            org.joml.Vector3f matrixScale = new org.joml.Vector3f();
            matrix.getScale(matrixScale);
            LogManager.serverDebug("ServerGeoModelManager", 
                "Matrix actual scale for bone '{}': ({}, {}, {})", 
                boneName, matrixScale.x(), matrixScale.y(), matrixScale.z());
            
            boneMatrices.put(boneName, matrix);
            
        } catch (Exception e) {
            LogManager.serverError("ServerGeoModelManager", "Failed to build matrix for bone '" + boneName + "': " + e.getMessage(), e);
            return null;
        }
        
        return boneMatrices.get(boneName);
    }

    private long getUniqueIdForAnimatable(GeoAnimatable animatable) {
        // 对于实体类型的GeoAnimatable，使用实体ID
        if (animatable instanceof net.minecraft.world.entity.Entity entity) {
            return entity.getId();
        }
        // 对于物品类型的GeoAnimatable，需要特殊处理
        return System.identityHashCode(animatable);
    }

    //检查是否已初始化
    public boolean isInitialized() {
        return initialized;
    }

    //获取所有已加载的模型位置
    public Set<ResourceLocation> getLoadedModelLocations() {
        return modelShelf.keySet();
    }

    //获取模型数量
    public int getModelCount() {
        return modelShelf.size();
    }

    //获取模型架（内部使用）
    public Map<ResourceLocation, ModelCollection> getModelShelf() {
        return modelShelf;
    }

    //获取BakedGeoModel（用于ServerCoreGeoModel）
    public BakedGeoModel getBakedModel(ResourceLocation loc) {
        return getModel(loc);
    }
}
