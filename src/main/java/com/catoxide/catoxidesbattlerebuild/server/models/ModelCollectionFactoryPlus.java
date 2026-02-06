package com.catoxide.catoxidesbattlerebuild.server.models;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.loading.FileLoader;
import software.bernie.geckolib.loading.json.raw.Model;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.loading.object.BakedModelFactory;
import software.bernie.geckolib.loading.object.GeometryTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一工厂类，负责从 JSON 文件中创建所有模型相关对象
 * 一次文件读取，同时创建：
 * - ModelCollection（包含 CoreGeoModel、BakedGeoModel、BakedAnimations、AnimationProcessor）
 * - BoneCollection（骨骼数据）
 * - CubeCollection（方块数据）
 * 
 * 避免重复的文件读取操作，提高性能
 */
public class ModelCollectionFactoryPlus {

    // 缓存：模型位置 -> ModelCollection
    private final Map<ResourceLocation, ModelCollection> modelCache = new ConcurrentHashMap<>();
    
    // 缓存：模型位置 -> 骨骼映射表
    private final Map<ResourceLocation, Map<String, BoneCollection>> boneCache = new ConcurrentHashMap<>();

    /**
     * 创建完整的模型数据集合
     * 一次文件读取，同时创建所有需要的对象
     * 
     * @param modelLocation 模型文件位置
     * @param resourceManager 资源管理器
     * @return UnifiedModelCollection 包含所有模型数据
     */
    public UnifiedModelCollection createModelData(
            ResourceLocation modelLocation,
            ResourceManager resourceManager
    ) {
        // 检查缓存
        if (modelCache.containsKey(modelLocation) && boneCache.containsKey(modelLocation)) {
            return new UnifiedModelCollection(
                    modelCache.get(modelLocation),
                    boneCache.get(modelLocation)
            );
        }

        try {
            // 1. 加载原始模型文件（唯一一次文件读取）
            Model rawModel = loadRawModel(modelLocation, resourceManager);
            
            // 2. 创建 BakedGeoModel（从原始模型）
            BakedGeoModel bakedModel = createBakedGeoModel(modelLocation, rawModel);
            
            // 3. 加载动画文件
            BakedAnimations animations = loadBakedAnimation(modelLocation, resourceManager);
            
            // 4. 创建 ServerCoreGeoModel
            ServerCoreGeoModel<GeoAnimatable> coreModel = 
                    new ServerCoreGeoModel<>(modelLocation, bakedModel, animations);
            
            // 5. 创建 AnimationProcessor
            AnimationProcessor animationProcessor = createAnimationProcessor(coreModel, bakedModel);
            
            // 6. 创建 ModelCollection
            ModelCollection modelCollection = new ModelCollection(
                    coreModel, bakedModel, animations, animationProcessor
            );
            
            // 7. 从 BakedGeoModel 中提取骨骼数据
            Map<String, BoneCollection> boneMap = buildBoneCollections(bakedModel, rawModel);
            
            // 8. 缓存结果
            modelCache.put(modelLocation, modelCollection);
            boneCache.put(modelLocation, boneMap);
            
            GeckoLib.LOGGER.debug("UnifiedModelFactory created model data for: {} ({} bones)", 
                    modelLocation, boneMap.size());
            
            return new UnifiedModelCollection(modelCollection, boneMap);
            
        } catch (Exception e) {
            GeckoLib.LOGGER.error("UnifiedModelFactory failed to create model data for: {}", 
                    modelLocation, e);
            throw new RuntimeException("Model creation failed: " + modelLocation, e);
        }
    }

    /**
     * 加载原始模型文件
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
     * 创建 BakedGeoModel
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
     * 加载动画文件
     */
    private BakedAnimations loadBakedAnimation(ResourceLocation modelLocation, ResourceManager resourceManager) {
        try {
            String path = modelLocation.getPath();
            // 移除 "geo/" 前缀和 ".geo.json" 后缀
            if (path.startsWith("geo/")) {
                path = path.substring(4);
            }
            if (path.endsWith(".geo.json")) {
                path = path.substring(0, path.length() - 9); // ".geo.json" 是9个字符
            }
            // 添加 "animations/" 前缀和 ".animation.json" 后缀
            String animationPath = "animations/" + path + ".animation.json";
            ResourceLocation animationLocation =ResourceLocation.fromNamespaceAndPath(modelLocation.getNamespace(), animationPath);
            BakedAnimations bakedAnimation = FileLoader.loadAnimationsFile(animationLocation, resourceManager);
            
            if (bakedAnimation == null) {
                GeckoLib.LOGGER.warn("Failed to load animation for: {} (may not exist)", modelLocation);
            }
            return bakedAnimation;
        } catch (Exception e) {
            GeckoLib.LOGGER.warn("Error loading animation for: {} (may not exist)", modelLocation);
            return null;
        }
    }

    /**
     * 创建 AnimationProcessor
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
     * 从 BakedGeoModel 中构建所有骨骼
     */
    private Map<String, BoneCollection> buildBoneCollections(BakedGeoModel bakedModel, Model rawModel) {
        // 递归收集所有骨骼（包括子骨骼）
        List<GeoBone> allBones = collectAllBones(bakedModel.getBones());
        
        // 创建 Builder 映射表
        Map<String, BoneCollection.Builder> builderMap = new ConcurrentHashMap<>();
        
        // 第一遍：为每个骨骼创建 Builder
        for (GeoBone geoBone : allBones) {
            try {
                // 创建 CubeCollection 列表
                List<CubeCollection> cubes = createCubeCollections(geoBone, rawModel);
                
                // 构建变换矩阵
                Matrix4f localMatrix = geoBone.getLocalSpaceMatrix();
                Matrix4f worldMatrix = geoBone.getWorldSpaceMatrix();
                
                // 创建 Builder
                BoneCollection.Builder builder = BoneCollection.builder(geoBone.getName())
                        .sourceBone(geoBone)
                        .parent(geoBone.getParent() != null ? geoBone.getParent().getName() : null)
                        .localMatrix(localMatrix)
                        .worldMatrix(worldMatrix)
                        .cubes(cubes);
                
                builderMap.put(geoBone.getName(), builder);
                
            } catch (Exception e) {
                GeckoLib.LOGGER.error("Failed to create Builder for bone: {}", 
                        geoBone.getName(), e);
            }
        }
        
        // 第二遍：建立父子关系
        for (GeoBone geoBone : allBones) {
            BoneCollection.Builder builder = builderMap.get(geoBone.getName());
            if (builder == null) continue;
            
            // 添加所有子骨骼
            for (GeoBone potentialChild : allBones) {
                GeoBone childParent = potentialChild.getParent();
                if (childParent != null && geoBone.getName().equals(childParent.getName())) {
                    builder.child(potentialChild.getName());
                }
            }
        }
        
        // 构建最终的 BoneCollection 实例
        Map<String, BoneCollection> boneMap = new ConcurrentHashMap<>();
        for (Map.Entry<String, BoneCollection.Builder> entry : builderMap.entrySet()) {
            try {
                BoneCollection boneCollection = entry.getValue().build();
                boneMap.put(entry.getKey(), boneCollection);
            } catch (Exception e) {
                GeckoLib.LOGGER.error("Failed to build BoneCollection for bone: {}", 
                        entry.getKey(), e);
            }
        }
        
        return boneMap;
    }

    /**
     * 递归收集所有骨骼（包括子骨骼）
     * @param topLevelBones 顶级骨骼列表
     * @return 所有骨骼的列表
     */
    private List<GeoBone> collectAllBones(List<? extends software.bernie.geckolib.core.animatable.model.CoreGeoBone> topLevelBones) {
        List<GeoBone> allBones = new ArrayList<>();
        collectBonesRecursively(topLevelBones, allBones);
        return allBones;
    }

    /**
     * 递归收集骨骼
     * @param bones 当前层级的骨骼列表
     * @param allBones 收集所有骨骼的列表
     */
    private void collectBonesRecursively(List<? extends software.bernie.geckolib.core.animatable.model.CoreGeoBone> bones, List<GeoBone> allBones) {
        for (software.bernie.geckolib.core.animatable.model.CoreGeoBone bone : bones) {
            if (bone instanceof GeoBone) {
                GeoBone geoBone = (GeoBone) bone;
                allBones.add(geoBone);
                // 递归处理子骨骼
                if (geoBone.getChildBones() != null && !geoBone.getChildBones().isEmpty()) {
                    collectBonesRecursively(geoBone.getChildBones(), allBones);
                }
            }
        }
    }

    /**
     * 为单个 GeoBone 创建 BoneCollection
     */
    private BoneCollection createBoneCollection(GeoBone geoBone, Model rawModel) {
        try {
            // 创建 CubeCollection 列表
            List<CubeCollection> cubes = createCubeCollections(geoBone, rawModel);
            
            // 构建变换矩阵
            Matrix4f localMatrix = geoBone.getLocalSpaceMatrix();
            Matrix4f worldMatrix = geoBone.getWorldSpaceMatrix(); // 初始时世界矩阵等于局部矩阵
            
            // 构建 BoneCollection
            return BoneCollection.builder(geoBone.getName())
                    .sourceBone(geoBone)
                    .parent(geoBone.getParent() != null ? geoBone.getParent().getName() : null)
                    .localMatrix(localMatrix)
                    .worldMatrix(worldMatrix)
                    .cubes(cubes)
                    .build();
            
        } catch (Exception e) {
            GeckoLib.LOGGER.error("Failed to create BoneCollection for bone: {}", 
                    geoBone.getName(), e);
            return null;
        }
    }

    /**
     * 为骨骼创建所有方块
     */
    private List<CubeCollection> createCubeCollections(GeoBone geoBone, Model rawModel) {
        List<CubeCollection> cubes = new ArrayList<>();
        
        // TODO: 将来可以直接从rawModel中解析cube数据，而不是通过GeoCube
        // 这样可以确保获得最原始的origin/offset信息
        
        // 从 GeoBone 中获取 cubes（暂时仍使用GeckoLib提供的GeoCube对象）
        if (geoBone.getCubes() != null) {
            for (int i = 0; i < geoBone.getCubes().size(); i++) {
                GeoCube geoCube = geoBone.getCubes().get(i);
                
                // 从GeoCube获取数据（这是临时方案）
                String id = String.format("%s_cube_%d", geoBone.getName(), i);
                Vector3f pivot = geoCube.pivot() != null ? geoCube.pivot().toVector3f() : new Vector3f();
                Vector3f size = geoCube.size() != null ? geoCube.size().toVector3f() : new Vector3f(1, 1, 1);
                Vector3f rotation = geoCube.rotation() != null ? geoCube.rotation().toVector3f() : new Vector3f();
                Vector3f originOffset = geoCube.offset() != null ? geoCube.offset().toVector3f() : new Vector3f();
                
                CubeCollection cube = CubeCollection.fromJsonData(id, pivot, size, rotation, originOffset);
                cubes.add(cube);
            }
        }
        
        return cubes;
    }

    /**
     * 获取指定模型的 ModelCollection
     */
    public ModelCollection getModelCollection(ResourceLocation modelLocation) {
        ModelCollection modelCollection = modelCache.get(modelLocation);
        if (modelCollection == null) {
            throw new IllegalStateException("ModelCollection not loaded for model: " + modelLocation);
        }
        return modelCollection;
    }

    /**
     * 获取指定模型的所有骨骼
     */
    public Map<String, BoneCollection> getBoneCollections(ResourceLocation modelLocation) {
        Map<String, BoneCollection> boneMap = boneCache.get(modelLocation);
        if (boneMap == null) {
            throw new IllegalStateException("Bone collections not loaded for model: " + modelLocation);
        }
        return boneMap;
    }

    /**
     * 获取指定模型中指定名称的骨骼
     */
    public BoneCollection getBoneCollection(ResourceLocation modelLocation, String boneName) {
        Map<String, BoneCollection> boneMap = getBoneCollections(modelLocation);
        return boneMap.get(boneName);
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        modelCache.clear();
        boneCache.clear();
        GeckoLib.LOGGER.debug("UnifiedModelFactory cache cleared");
    }

    /**
     * 批量加载多个模型
     */
    public Map<ResourceLocation, UnifiedModelCollection> loadMultipleModels(
            List<ResourceLocation> modelLocations,
            ResourceManager resourceManager
    ) {
        Map<ResourceLocation, UnifiedModelCollection> result = new ConcurrentHashMap<>();
        
        for (ResourceLocation modelLocation : modelLocations) {
            try {
                UnifiedModelCollection data = createModelData(modelLocation, resourceManager);
                result.put(modelLocation, data);
            } catch (Exception e) {
                GeckoLib.LOGGER.error("Failed to load model data for: {}", modelLocation, e);
            }
        }
        
        return result;
    }

    /**
     * 统一模型数据集合
     * 包含 ModelCollection 和 BoneCollection
     */
    public static class UnifiedModelCollection {
        private final ModelCollection modelCollection;
        private final Map<String, BoneCollection> boneCollections;

        public UnifiedModelCollection(
                ModelCollection modelCollection,
                Map<String, BoneCollection> boneCollections
        ) {
            this.modelCollection = modelCollection;
            this.boneCollections = boneCollections;
        }

        public ModelCollection getModelCollection() {
            return modelCollection;
        }

        public Map<String, BoneCollection> getBoneCollections() {
            return boneCollections;
        }

        public BoneCollection getBone(String boneName) {
            return boneCollections.get(boneName);
        }
    }
}













