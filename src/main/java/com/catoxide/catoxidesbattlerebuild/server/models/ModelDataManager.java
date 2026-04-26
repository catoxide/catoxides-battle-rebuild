package com.catoxide.catoxidesbattlerebuild.server.models;

import com.catoxide.catoxidesbattlerebuild.server.geometry.BoneCollection;
import com.catoxide.catoxidesbattlerebuild.server.geometry.BoneCollectionFactory;
import com.catoxide.catoxidesbattlerebuild.server.geometry.CubeCollection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.GeckoLib;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一模型数据管理器
 * 负责从BakedGeoModel提取骨骼和立方体数据，并提供缓存机制
 */
public class ModelDataManager {

    private static final ModelDataManager INSTANCE = new ModelDataManager();

    // 缓存：模型位置 -> BoneModelData
    private final Map<ResourceLocation, BoneModelData> boneModelDataCache = new ConcurrentHashMap<>();

    private final BoneCollectionFactory boneCollectionFactory = new BoneCollectionFactory();
    
    // 初始化标志
    private volatile boolean initialized = false;

    private ModelDataManager() {}

    public static ModelDataManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 初始化模型数据管理器
     * 从ServerGeoModelManager获取所有已加载的模型并预加载BoneModelData
     */
    public void initialize(ResourceManager resourceManager) {
        GeckoLib.LOGGER.info("Initializing ModelDataManager...");

        // 从ServerGeoModelManager获取所有已加载的模型
        ServerGeoModelManager geoModelManager = ServerGeoModelManager.getInstance();
        Map<ResourceLocation, ModelCollection> modelShelf = geoModelManager.getModelShelf();

        // 预加载所有模型的BoneModelData
        for (Map.Entry<ResourceLocation, ModelCollection> entry : modelShelf.entrySet()) {
            ResourceLocation modelLocation = entry.getKey();

            try {
                // 直接从模型文件提取BoneModelData
                BoneModelData boneModelData = loadBoneModelDataFromFile(modelLocation, resourceManager);
                boneModelDataCache.put(modelLocation, boneModelData);

                GeckoLib.LOGGER.debug("Preloaded BoneModelData for model: {}", modelLocation);
            } catch (Exception e) {
                GeckoLib.LOGGER.error("Failed to preload BoneModelData for model: {}", modelLocation, e);
            }
        }

        GeckoLib.LOGGER.info("ModelDataManager initialized with {} models", boneModelDataCache.size());
        
        // 设置初始化标志
        initialized = true;
    }

    /**
     * 从模型文件加载BoneModelData
     * 使用BoneModelDataExtractor直接从原始模型文件提取数据
     */
    private BoneModelData loadBoneModelDataFromFile(ResourceLocation modelLocation, ResourceManager resourceManager) {
        try {
            // modelLocation 已经是完整的资源路径（如 catoxidesbattlerebuild:geo/modular_zombie.geo.json）
            // 直接使用它来获取资源
            GeckoLib.LOGGER.debug("Loading BoneModelData from: {}", modelLocation);

            // 从资源管理器获取输入流并提取BoneModelData
            try (java.io.InputStream inputStream = resourceManager.getResource(modelLocation).orElseThrow().open()) {
                return BoneModelDataExtractor.extractFromModelFile(modelLocation, inputStream);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load BoneModelData for: " + modelLocation, e);
        }
    }

    /**
     * 获取模型的BoneModelData
     */
    public BoneModelData getBoneModelData(ResourceLocation modelLocation) {
        return boneModelDataCache.get(modelLocation);
    }

    /**
     * 获取模型的BoneCollection
     */
    public Map<String, BoneCollection> getBoneCollections(long entityId, ResourceLocation modelLocation) {
        BoneModelData boneModelData = getBoneModelData(modelLocation);
        if (boneModelData == null) {
            GeckoLib.LOGGER.warn("BoneModelData not found for model: {}", modelLocation);
            return Collections.emptyMap();
        }
        return boneCollectionFactory.createBoneCollections(entityId, modelLocation, boneModelData);
    }

    /**
     * 获取模型的CubeCollection
     */
    public CubeCollection getCubeCollection(long entityId, ResourceLocation modelLocation) {
        BoneModelData boneModelData = getBoneModelData(modelLocation);
        if (boneModelData == null) {
            GeckoLib.LOGGER.warn("BoneModelData not found for model: {}", modelLocation);
            return null;
        }
        // 返回第一个骨骼的第一个立方体集合
        Map<String, BoneModelData.BoneStaticData> boneDataMap = boneModelData.boneStaticDataMap();
        if (boneDataMap.isEmpty()) {
            return null;
        }
        BoneModelData.BoneStaticData firstBone = boneDataMap.values().iterator().next();
        if (firstBone.cubeStaticDataList().isEmpty()) {
            return null;
        }
        return boneCollectionFactory.createCubeCollection(entityId, firstBone.boneName(), firstBone.cubeStaticDataList().get(0));
    }

    /**
     * 清除指定模型的缓存
     */
    public void clearCache(ResourceLocation modelLocation) {
        boneModelDataCache.remove(modelLocation);
        GeckoLib.LOGGER.debug("Cleared cache for model: {}", modelLocation);
    }

    /**
     * 清除所有缓存
     */
    public void clearAllCache() {
        boneModelDataCache.clear();
        GeckoLib.LOGGER.info("Cleared all ModelDataManager caches");
    }

    /**
     * 获取缓存统计信息
     */
    public Map<String, Integer> getCacheStats() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("boneModelData", boneModelDataCache.size());
        return stats;
    }
}