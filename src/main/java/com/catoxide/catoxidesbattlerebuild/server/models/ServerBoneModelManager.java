package com.catoxide.catoxidesbattlerebuild.server.models;

import com.catoxide.catoxidesbattlerebuild.server.entities.BoneCollection;
import com.catoxide.catoxidesbattlerebuild.server.entities.BoneCollectionFactory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Map;

/**
 * 简化版骨骼模型管理器
 * 不维护大型索引，只提供按需创建和基本缓存功能
 * 通过模型位置和标识符匹配来获取相应数据
 */
public class ServerBoneModelManager {

    private static ServerBoneModelManager instance;
    
    // 骨骼集合工厂
    private final BoneCollectionFactory boneCollectionFactory = new BoneCollectionFactory();

    private ServerBoneModelManager() {}

    public static ServerBoneModelManager getInstance() {
        if (instance == null) {
            instance = new ServerBoneModelManager();
        }
        return instance;
    }

    /**
     * 根据模型位置创建BoneCollection映射
     * 如果已有缓存则直接使用，否则从模型文件创建
     */
    public Map<String, BoneCollection> createBoneCollections(long entityId, ResourceLocation modelLocation, ResourceManager resourceManager) {
        return boneCollectionFactory.createBoneCollections(entityId, modelLocation, resourceManager);
    }

    /**
     * 获取已缓存的BoneModelData（如果存在）
     */
    public BoneModelData getBoneModelData(ResourceLocation modelLocation) {
        return boneCollectionFactory.getBoneModelData(modelLocation);
    }

    /**
     * 按需预加载模型数据
     */
    public void preloadBoneModelData(ResourceLocation modelLocation, ResourceManager resourceManager) {
        boneCollectionFactory.preloadBoneModelData(modelLocation, resourceManager);
    }

    /**
     * 清除指定模型的缓存
     */
    public void clearCache(ResourceLocation modelLocation) {
        boneCollectionFactory.clearCache(modelLocation);
    }

    /**
     * 清除所有缓存
     */
    public void clearAllCache() {
        boneCollectionFactory.clearAllCache();
    }
}