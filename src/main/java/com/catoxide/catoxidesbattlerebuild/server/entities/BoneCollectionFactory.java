package com.catoxide.catoxidesbattlerebuild.server.entities;

import com.catoxide.catoxidesbattlerebuild.server.models.BoneModelData;
import com.catoxide.catoxidesbattlerebuild.server.models.BoneModelDataExtractor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 骨骼集合工厂类
 * 使用BoneModelData来高效创建BoneCollection和CubeCollection实例
 * 避免重复解析模型文件，提高实体创建效率
 */
public class BoneCollectionFactory {

    // 缓存：模型位置 -> 骨骼模型数据
    private final Map<ResourceLocation, BoneModelData> boneModelDataCache = new ConcurrentHashMap<>();

    /**
     * 从BoneModelData创建BoneCollection映射
     * 这是主要的工厂方法，用于从预加载的静态数据创建实体特定的骨骼集合
     *
     * @param entityId 实体ID
     * @param modelLocation 模型位置
     * @param boneModelData 预加载的骨骼模型数据
     * @return 骨骼名称到BoneCollection的映射
     */
    public Map<String, BoneCollection> createBoneCollections(long entityId, ResourceLocation modelLocation, BoneModelData boneModelData) {
        Map<String, BoneCollection> boneCollectionMap = new HashMap<>();

        // 遍历所有骨骼静态数据并创建对应的BoneCollection
        for (Map.Entry<String, BoneModelData.BoneStaticData> entry : boneModelData.boneStaticDataMap().entrySet()) {
            String boneName = entry.getKey();
            BoneModelData.BoneStaticData boneStaticData = entry.getValue();

            // 从静态数据创建CubeCollection列表
            List<CubeCollection> cubeCollections = new ArrayList<>();
            for (BoneModelData.CubeStaticData cubeStaticData : boneStaticData.cubeStaticDataList()) {
                CubeCollection cubeCollection = CubeCollection.fromStaticData(
                        entityId,
                        boneName,
                        cubeStaticData
                );
                cubeCollections.add(cubeCollection);
            }

            // 使用静态数据创建BoneCollection
            BoneCollection boneCollection = BoneCollection.fromStaticData(
                    entityId,
                    boneName,
                    boneStaticData,
                    cubeCollections
            );

            boneCollectionMap.put(boneName, boneCollection);
        }

        // 设置父子骨骼关系
        setBoneHierarchy(boneCollectionMap, boneModelData);

        return boneCollectionMap;
    }

    /**
     * 设置骨骼层级关系
     */
    private void setBoneHierarchy(Map<String, BoneCollection> boneCollectionMap, BoneModelData boneModelData) {
        for (Map.Entry<String, BoneCollection> entry : boneCollectionMap.entrySet()) {
            String boneName = entry.getKey();
            BoneCollection boneCollection = entry.getValue();
            
            // 获取该骨骼的子骨骼名称
            List<String> childBones = boneModelData.getChildBones(boneName);
            boneCollection.setChildBoneNames(childBones);
            
            // 如果当前骨骼是其他骨骼的子骨骼，则更新父骨骼的子骨骼列表
            String parentBoneName = boneModelData.getParentBone(boneName);
            if (parentBoneName != null && boneCollectionMap.containsKey(parentBoneName)) {
                BoneCollection parentBoneCollection = boneCollectionMap.get(parentBoneName);
                parentBoneCollection.addChildBoneName(boneName);
            }
        }
    }

    /**
     * 从模型位置创建BoneCollection映射
     * 如果BoneModelData尚未缓存，则先加载它
     */
    public Map<String, BoneCollection> createBoneCollections(long entityId, ResourceLocation modelLocation, ResourceManager resourceManager) {
        // 检查缓存
        BoneModelData boneModelData = boneModelDataCache.get(modelLocation);
        if (boneModelData == null) {
            // 如果未缓存，从资源管理器加载
            boneModelData = loadBoneModelData(modelLocation, resourceManager);
            boneModelDataCache.put(modelLocation, boneModelData);
        }

        return createBoneCollections(entityId, modelLocation, boneModelData);
    }

    /**
     * 加载BoneModelData
     */
    private BoneModelData loadBoneModelData(ResourceLocation modelLocation, ResourceManager resourceManager) {
        try {
            // 构建资源路径
            ResourceLocation resourcePath =ResourceLocation.fromNamespaceAndPath(
                    modelLocation.getNamespace(),
                    "geckolib/" + modelLocation.getPath() + ".json"
            );

            // 从资源管理器获取输入流并提取BoneModelData
            try (java.io.InputStream inputStream = resourceManager.getResource(resourcePath).getInputStream()) {
                return BoneModelDataExtractor.extractFromModelFile(modelLocation, inputStream);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load BoneModelData for: " + modelLocation, e);
        }
    }

    /**
     * 预加载指定模型的BoneModelData到缓存中
     */
    public void preloadBoneModelData(ResourceLocation modelLocation, ResourceManager resourceManager) {
        if (!boneModelDataCache.containsKey(modelLocation)) {
            BoneModelData boneModelData = loadBoneModelData(modelLocation, resourceManager);
            boneModelDataCache.put(modelLocation, boneModelData);
        }
    }

    /**
     * 预加载多个模型的BoneModelData
     */
    public void preloadBoneModelData(List<ResourceLocation> modelLocations, ResourceManager resourceManager) {
        for (ResourceLocation modelLocation : modelLocations) {
            preloadBoneModelData(modelLocation, resourceManager);
        }
    }

    /**
     * 获取缓存的BoneModelData
     */
    public BoneModelData getBoneModelData(ResourceLocation modelLocation) {
        return boneModelDataCache.get(modelLocation);
    }

    /**
     * 检查是否已缓存指定模型的BoneModelData
     */
    public boolean hasBoneModelData(ResourceLocation modelLocation) {
        return boneModelDataCache.containsKey(modelLocation);
    }

    /**
     * 清除指定模型的缓存
     */
    public void clearCache(ResourceLocation modelLocation) {
        boneModelDataCache.remove(modelLocation);
    }

    /**
     * 清除所有缓存
     */
    public void clearAllCache() {
        boneModelDataCache.clear();
    }

    /**
     * 获取缓存的模型数量
     */
    public int getCachedModelCount() {
        return boneModelDataCache.size();
    }

    /**
     * 从BoneModelData创建单一BoneCollection
     */
    public BoneCollection createBoneCollection(long entityId, String boneName, BoneModelData.BoneStaticData boneStaticData) {
        List<CubeCollection> cubeCollections = new ArrayList<>();
        for (BoneModelData.CubeStaticData cubeStaticData : boneStaticData.cubeStaticDataList()) {
            CubeCollection cubeCollection = CubeCollection.fromStaticData(
                    entityId,
                    boneName,
                    cubeStaticData
            );
            cubeCollections.add(cubeCollection);
        }

        return BoneCollection.fromStaticData(
                entityId,
                boneStaticData.boneName(),
                boneStaticData,
                cubeCollections
        );
    }

    /**
     * 从BoneModelData创建单一CubeCollection
     */
    public CubeCollection createCubeCollection(long entityId, String boneName, BoneModelData.CubeStaticData cubeStaticData) {
        return CubeCollection.fromStaticData(
                entityId,
                boneName,
                cubeStaticData
        );
    }
}