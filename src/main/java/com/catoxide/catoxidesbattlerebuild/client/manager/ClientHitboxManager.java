package com.catoxide.catoxidesbattlerebuild.client.manager;

import com.catoxide.catoxidesbattlerebuild.client.geometry.*;
import com.catoxide.catoxidesbattlerebuild.client.models.ClientBoneModelData;
import com.catoxide.catoxidesbattlerebuild.client.models.ClientModelCollection;
import com.catoxide.catoxidesbattlerebuild.client.models.ClientModelManager;
import com.catoxide.catoxidesbattlerebuild.client.resolver.BoneMatrixResolver;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端受击盒管理器
 * 负责管理客户端的受击盒数据和解算
 */
public class ClientHitboxManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientHitboxManager.class);
    
    private static ClientHitboxManager instance;
    
    // 实体UUID -> 实体集合
    private final Map<UUID, ClientEntityCollection> entityCollections;
    
    // 实体UUID -> 骨骼集合映射
    private final Map<UUID, Map<String, ClientBoneCollection>> boneCollectionsMap;
    
    // 实体UUID -> 立方体集合映射
    private final Map<UUID, Map<String, List<ClientCubeCollection>>> cubeCollectionsMap;
    
    // 性能统计
    private long lastUpdateTime = 0;
    private int updateCount = 0;
    
    private ClientHitboxManager() {
        this.entityCollections = new ConcurrentHashMap<>();
        this.boneCollectionsMap = new ConcurrentHashMap<>();
        this.cubeCollectionsMap = new ConcurrentHashMap<>();
        LOGGER.info("[ClientHitboxManager] ClientHitboxManager initialized");
    }
    
    /**
     * 获取单例实例
     */
    public static ClientHitboxManager getInstance() {
        if (instance == null) {
            instance = new ClientHitboxManager();
        }
        return instance;
    }
    
    /**
     * 初始化管理器
     */
    public void initialize() {
        LOGGER.info("[ClientHitboxManager] Initializing hitbox manager...");
        entityCollections.clear();
        boneCollectionsMap.clear();
        cubeCollectionsMap.clear();
        lastUpdateTime = System.currentTimeMillis();
        updateCount = 0;
        LOGGER.info("[ClientHitboxManager] Hitbox manager initialization completed");
    }
    
    /**
     * 注册实体
     */
    public void registerEntity(UUID entityUuid, Entity entity, ResourceLocation modelLocation) {
        LOGGER.info("[ClientHitboxManager] Registering entity: uuid={}, model={}", entityUuid, modelLocation);
        
        // 获取模型集合
        ClientModelCollection modelCollection = ClientModelManager.getInstance().getModelCollection(modelLocation);
        if (modelCollection == null) {
            LOGGER.error("[ClientHitboxManager] Model collection not found: {}", modelLocation);
            return;
        }
        
        // 创建实体集合
        Vec3 modelPosition = entity.position();
        ClientEntityCollection entityCollection = ClientEntityCollection.create(
            entityUuid,
            modelPosition,
            modelCollection,
            entity
        );
        entityCollections.put(entityUuid, entityCollection);
        
        // 创建骨骼集合
        ClientBoneModelData boneModelData = modelCollection.getBoneModelData();
        Map<String, ClientBoneCollection> boneCollections = new ConcurrentHashMap<>();
        Map<String, List<ClientCubeCollection>> cubeCollections = new ConcurrentHashMap<>();
        
        for (String boneName : boneModelData.boneStaticDataMap().keySet()) {
            ClientBoneModelData.BoneStaticData boneStaticData = boneModelData.getBoneStaticData(boneName);
            ClientBoneCollection boneCollection = ClientBoneCollection.fromStaticData(
                entity.getId(),
                boneStaticData
            );
            boneCollections.put(boneName, boneCollection);
            
            // 收集立方体集合
            List<ClientCubeCollection> cubes = boneCollection.getCubeCollections();
            cubeCollections.put(boneName, cubes);
        }
        
        boneCollectionsMap.put(entityUuid, boneCollections);
        cubeCollectionsMap.put(entityUuid, cubeCollections);
        
        LOGGER.info("[ClientHitboxManager] Registered entity with {} bones: uuid={}", 
                boneCollections.size(), entityUuid);
    }
    
    /**
     * 更新实体受击盒
     */
    public void updateEntityHitboxes(UUID entityUuid) {
        ClientEntityCollection entityCollection = entityCollections.get(entityUuid);
        if (entityCollection == null) {
            LOGGER.warn("[ClientHitboxManager] Entity not found: uuid={}", entityUuid);
            return;
        }
        
        Map<String, ClientBoneCollection> boneCollections = boneCollectionsMap.get(entityUuid);
        if (boneCollections == null) {
            LOGGER.warn("[ClientHitboxManager] Bone collections not found: uuid={}", entityUuid);
            return;
        }
        
        ClientModelCollection modelCollection = entityCollection.modelCollection();
        ClientBoneModelData boneModelData = modelCollection.getBoneModelData();
        
        // 解算骨骼矩阵
        BoneMatrixResolver.resolveEntityBones(entityCollection, boneCollections, boneModelData);
        
        // 更新立方体顶点数据
        Map<String, Map<String, List<Vec3>>> cubeVertices = entityCollection.dynamicData().getCubeVertices();
        for (Map.Entry<String, List<ClientCubeCollection>> entry : cubeCollectionsMap.get(entityUuid).entrySet()) {
            String boneName = entry.getKey();
            List<ClientCubeCollection> cubes = entry.getValue();
            
            Map<String, List<Vec3>> boneCubes = new ConcurrentHashMap<>();
            for (ClientCubeCollection cube : cubes) {
                boneCubes.put(cube.getId(), cube.getWorldVertices());
            }
            cubeVertices.put(boneName, boneCubes);
        }
        
        updateCount++;
        LOGGER.debug("[ClientHitboxManager] Updated entity hitboxes: uuid={}, updateCount={}", 
                entityUuid, updateCount);
    }
    
    /**
     * 获取实体集合
     */
    public ClientEntityCollection getEntityCollection(UUID entityUuid) {
        ClientEntityCollection collection = entityCollections.get(entityUuid);
        if (collection == null) {
            LOGGER.warn("[ClientHitboxManager] Entity collection not found: uuid={}", entityUuid);
        }
        return collection;
    }
    
    /**
     * 获取骨骼集合
     */
    public Map<String, ClientBoneCollection> getBoneCollections(UUID entityUuid) {
        Map<String, ClientBoneCollection> collections = boneCollectionsMap.get(entityUuid);
        if (collections == null) {
            LOGGER.warn("[ClientHitboxManager] Bone collections not found: uuid={}", entityUuid);
            return Map.of();
        }
        return new ConcurrentHashMap<>(collections);
    }
    
    /**
     * 获取立方体集合
     */
    public Map<String, List<ClientCubeCollection>> getCubeCollections(UUID entityUuid) {
        Map<String, List<ClientCubeCollection>> collections = cubeCollectionsMap.get(entityUuid);
        if (collections == null) {
            LOGGER.warn("[ClientHitboxManager] Cube collections not found: uuid={}", entityUuid);
            return Map.of();
        }
        Map<String, List<ClientCubeCollection>> copy = new ConcurrentHashMap<>();
        collections.forEach((boneName, cubes) -> {
            copy.put(boneName, new ArrayList<>(cubes));
        });
        return copy;
    }
    
    /**
     * 检查点是否在实体的任意受击盒内
     */
    public boolean containsPoint(UUID entityUuid, Vec3 point) {
        Map<String, List<ClientCubeCollection>> cubeCollections = getCubeCollections(entityUuid);
        for (List<ClientCubeCollection> cubes : cubeCollections.values()) {
            for (ClientCubeCollection cube : cubes) {
                if (cube.containsPoint(point)) {
                    LOGGER.debug("[ClientHitboxManager] Point found in cube: uuid={}, cubeId={}", 
                            entityUuid, cube.getId());
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 获取包含指定点的骨骼名称
     */
    public String getBoneContainingPoint(UUID entityUuid, Vec3 point) {
        Map<String, List<ClientCubeCollection>> cubeCollections = getCubeCollections(entityUuid);
        for (Map.Entry<String, List<ClientCubeCollection>> entry : cubeCollections.entrySet()) {
            String boneName = entry.getKey();
            for (ClientCubeCollection cube : entry.getValue()) {
                if (cube.containsPoint(point)) {
                    LOGGER.debug("[ClientHitboxManager] Point found in bone: uuid={}, boneName={}", 
                            entityUuid, boneName);
                    return boneName;
                }
            }
        }
        return null;
    }
    
    /**
     * 清理实体数据
     */
    public void clearEntity(UUID entityUuid) {
        entityCollections.remove(entityUuid);
        boneCollectionsMap.remove(entityUuid);
        cubeCollectionsMap.remove(entityUuid);
        LOGGER.info("[ClientHitboxManager] Cleared entity data: uuid={}", entityUuid);
    }
    
    /**
     * 清理所有数据
     */
    public void clear() {
        int entityCount = entityCollections.size();
        entityCollections.clear();
        boneCollectionsMap.clear();
        cubeCollectionsMap.clear();
        lastUpdateTime = System.currentTimeMillis();
        updateCount = 0;
        LOGGER.info("[ClientHitboxManager] Cleared all data: entityCount={}", entityCount);
    }
    
    /**
     * 获取所有骨骼集合映射
     */
    public Map<UUID, Map<String, ClientBoneCollection>> getAllBoneCollections() {
        Map<UUID, Map<String, ClientBoneCollection>> copy = new ConcurrentHashMap<>();
        boneCollectionsMap.forEach((entityUuid, boneCollections) -> {
            copy.put(entityUuid, new ConcurrentHashMap<>(boneCollections));
        });
        return copy;
    }
    
    /**
     * 获取性能统计
     */
    public String getPerformanceStats() {
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - lastUpdateTime;
        double updatesPerSecond = elapsedTime > 0 ? (updateCount * 1000.0) / elapsedTime : 0;
        
        String stats = String.format(
            "[ClientHitboxManager] Performance Stats: entities=%d, updates=%d, elapsed=%dms, updates/sec=%.2f",
            entityCollections.size(),
            updateCount,
            elapsedTime,
            updatesPerSecond
        );
        
        LOGGER.info(stats);
        return stats;
    }
}