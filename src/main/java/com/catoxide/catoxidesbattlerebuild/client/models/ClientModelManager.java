package com.catoxide.catoxidesbattlerebuild.client.models;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端模型管理器
 * 负责管理客户端模型集合和静态数据
 * 对齐服务端ServerGeoModelManager
 */
public class ClientModelManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientModelManager.class);
    
    private static ClientModelManager instance;
    
    // 模型集合映射（模型位置 -> 模型集合）
    private final Map<ResourceLocation, ClientModelCollection> modelCollections;
    
    // 骨骼模型数据映射（模型位置 -> 骨骼模型数据）
    private final Map<ResourceLocation, ClientBoneModelData> boneModelDataMap;
    
    private ClientModelManager() {
        this.modelCollections = new ConcurrentHashMap<>();
        this.boneModelDataMap = new ConcurrentHashMap<>();
        LOGGER.info("[ClientModelManager] ClientModelManager initialized");
    }
    
    /**
     * 获取单例实例
     */
    public static ClientModelManager getInstance() {
        if (instance == null) {
            instance = new ClientModelManager();
        }
        return instance;
    }
    
    /**
     * 初始化模型管理器
     */
    public void initialize() {
        LOGGER.info("[ClientModelManager] Initializing model manager...");
        modelCollections.clear();
        boneModelDataMap.clear();
        LOGGER.info("[ClientModelManager] Model manager initialization completed");
    }
    
    /**
     * 注册模型集合
     */
    public void registerModelCollection(ClientModelCollection modelCollection) {
        ResourceLocation location = modelCollection.modelLocation();
        modelCollections.put(location, modelCollection);
        LOGGER.info("[ClientModelManager] Registered model collection: {} at {}", 
                modelCollection.modelName(), location);
    }
    
    /**
     * 注册骨骼模型数据
     */
    public void registerBoneModelData(ResourceLocation modelLocation, ClientBoneModelData boneModelData) {
        boneModelDataMap.put(modelLocation, boneModelData);
        LOGGER.info("[ClientModelManager] Registered bone model data for model: {}", modelLocation);
    }
    
    /**
     * 获取模型集合
     */
    public ClientModelCollection getModelCollection(ResourceLocation modelLocation) {
        ClientModelCollection collection = modelCollections.get(modelLocation);
        if (collection == null) {
            LOGGER.warn("[ClientModelManager] Model collection not found: {}", modelLocation);
        } else {
            LOGGER.debug("[ClientModelManager] Retrieved model collection: {}", modelLocation);
        }
        return collection;
    }
    
    /**
     * 获取骨骼模型数据
     */
    public ClientBoneModelData getBoneModelData(ResourceLocation modelLocation) {
        ClientBoneModelData data = boneModelDataMap.get(modelLocation);
        if (data == null) {
            LOGGER.warn("[ClientModelManager] Bone model data not found: {}", modelLocation);
        } else {
            LOGGER.debug("[ClientModelManager] Retrieved bone model data: {}", modelLocation);
        }
        return data;
    }
    
    /**
     * 检查模型是否已注册
     */
    public boolean hasModel(ResourceLocation modelLocation) {
        boolean has = modelCollections.containsKey(modelLocation);
        LOGGER.debug("[ClientModelManager] Checking model existence: {} = {}", modelLocation, has);
        return has;
    }
    
    /**
     * 清理所有模型数据
     */
    public void clear() {
        int modelCount = modelCollections.size();
        int boneDataCount = boneModelDataMap.size();
        modelCollections.clear();
        boneModelDataMap.clear();
        LOGGER.info("[ClientModelManager] Cleared {} model collections and {} bone model data entries", 
                modelCount, boneDataCount);
    }
    
    /**
     * 预留：从网络接收模型数据
     */
    public void receiveModelDataFromNetwork(ResourceLocation modelLocation, ClientBoneModelData boneModelData) {
        LOGGER.info("[ClientModelManager] Receiving model data from network: {}", modelLocation);
        registerBoneModelData(modelLocation, boneModelData);
        // TODO: 实现网络数据接收逻辑
    }
}
