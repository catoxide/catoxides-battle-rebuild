package com.catoxide.catoxidesbattlerebuild.client.bodypart;

import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端实体身体部位系统
 * 管理实体的身体部位和伤害处理
 * 对齐服务端EntityBodySystem
 */
public class ClientEntityBodySystem {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientEntityBodySystem.class);
    
    private static ClientEntityBodySystem instance;
    
    // 实体UUID -> 身体部位列表
    private final Map<UUID, List<ClientBodyPart>> entityBodyParts;
    
    // 实体UUID -> 骨骼名称 -> BodyPart映射
    private final Map<UUID, Map<String, ClientBodyPart>> boneToBodyPartMap;
    
    // 实体UUID -> 部位ID -> BodyPart映射
    private final Map<UUID, Map<String, ClientBodyPart>> partIdToBodyPartMap;
    
    private ClientEntityBodySystem() {
        this.entityBodyParts = new ConcurrentHashMap<>();
        this.boneToBodyPartMap = new ConcurrentHashMap<>();
        this.partIdToBodyPartMap = new ConcurrentHashMap<>();
        LOGGER.info("[ClientEntityBodySystem] ClientEntityBodySystem initialized");
    }
    
    /**
     * 获取单例实例
     */
    public static ClientEntityBodySystem getInstance() {
        if (instance == null) {
            instance = new ClientEntityBodySystem();
        }
        return instance;
    }
    
    /**
     * 初始化系统
     */
    public void initialize() {
        LOGGER.info("[ClientEntityBodySystem] Initializing entity body system...");
        entityBodyParts.clear();
        boneToBodyPartMap.clear();
        partIdToBodyPartMap.clear();
        LOGGER.info("[ClientEntityBodySystem] Entity body system initialization completed");
    }
    
    /**
     * 注册实体的身体部位
     */
    public void registerEntityBodyParts(UUID entityUuid, List<ClientBodyPart> bodyParts) {
        LOGGER.info("[ClientEntityBodySystem] Registering body parts for entity: uuid={}, partCount={}", 
                entityUuid, bodyParts.size());
        
        entityBodyParts.put(entityUuid, new ArrayList<>(bodyParts));
        
        // 初始化映射
        Map<String, ClientBodyPart> boneMap = new ConcurrentHashMap<>();
        Map<String, ClientBodyPart> partMap = new ConcurrentHashMap<>();
        
        for (ClientBodyPart part : bodyParts) {
            // 注册部位ID映射
            partMap.put(part.getPartId(), part);
            
            // 注册骨骼映射
            for (ClientBodyUnit unit : part.getBodyUnits()) {
                boneMap.put(unit.getBoneName(), part);
            }
        }
        
        boneToBodyPartMap.put(entityUuid, boneMap);
        partIdToBodyPartMap.put(entityUuid, partMap);
        
        LOGGER.debug("[ClientEntityBodySystem] Registered {} body parts with {} bone mappings for entity: {}", 
                bodyParts.size(), boneMap.size(), entityUuid);
    }
    
    /**
     * 处理实体伤害
     * 根据击中的骨骼找到对应的BodyPart
     */
    public void processEntityDamage(UUID entityUuid, String boneName, double rawDamage, Vec3 hitPosition) {
        LOGGER.info("[ClientEntityBodySystem] Processing damage: uuid={}, boneName={}, rawDamage={}, hitPosition={}", 
                entityUuid, boneName, rawDamage, hitPosition);
        
        // 找到对应的BodyPart
        ClientBodyPart targetPart = getBodyPartByBone(entityUuid, boneName);
        
        if (targetPart == null) {
            LOGGER.warn("[ClientEntityBodySystem] No body part found for bone: boneName={}, entityUuid={}", 
                    boneName, entityUuid);
            return;
        }
        
        // 让BodyPart处理伤害
        targetPart.receiveDamage(boneName, rawDamage, hitPosition);
    }
    
    /**
     * 根据骨骼名称获取BodyPart
     */
    public ClientBodyPart getBodyPartByBone(UUID entityUuid, String boneName) {
        Map<String, ClientBodyPart> boneMap = boneToBodyPartMap.get(entityUuid);
        if (boneMap == null) {
            LOGGER.warn("[ClientEntityBodySystem] Bone map not found for entity: entityUuid={}", entityUuid);
            return null;
        }
        
        ClientBodyPart part = boneMap.get(boneName);
        if (part == null) {
            LOGGER.debug("[ClientEntityBodySystem] Body part not found for bone: boneName={}, entityUuid={}", 
                    boneName, entityUuid);
        }
        
        return part;
    }
    
    /**
     * 根据部位ID获取BodyPart
     */
    public ClientBodyPart getBodyPartById(UUID entityUuid, String partId) {
        Map<String, ClientBodyPart> partMap = partIdToBodyPartMap.get(entityUuid);
        if (partMap == null) {
            LOGGER.warn("[ClientEntityBodySystem] Part map not found for entity: entityUuid={}", entityUuid);
            return null;
        }
        
        ClientBodyPart part = partMap.get(partId);
        if (part == null) {
            LOGGER.debug("[ClientEntityBodySystem] Body part not found by id: partId={}, entityUuid={}", 
                    partId, entityUuid);
        }
        
        return part;
    }
    
    /**
     * 获取实体的所有身体部位
     */
    public List<ClientBodyPart> getEntityBodyParts(UUID entityUuid) {
        List<ClientBodyPart> parts = entityBodyParts.get(entityUuid);
        if (parts == null) {
            LOGGER.warn("[ClientEntityBodySystem] No body parts found for entity: entityUuid={}", entityUuid);
            return List.of();
        }
        return new ArrayList<>(parts);
    }
    
    /**
     * 检查实体是否有致命部位被破坏
     */
    public boolean hasCriticalPartDestroyed(UUID entityUuid) {
        List<ClientBodyPart> parts = getEntityBodyParts(entityUuid);
        for (ClientBodyPart part : parts) {
            if (part.isCriticalPart() && part.isDestroyed()) {
                LOGGER.info("[ClientEntityBodySystem] Critical part destroyed: entityUuid={}, partId={}, partName={}", 
                        entityUuid, part.getPartId(), part.getPartName());
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查实体是否存活
     */
    public boolean isEntityAlive(UUID entityUuid) {
        List<ClientBodyPart> parts = getEntityBodyParts(entityUuid);
        if (parts.isEmpty()) {
            LOGGER.debug("[ClientEntityBodySystem] No body parts for entity: entityUuid={}", entityUuid);
            return true;
        }
        
        // 如果有致命部位被破坏，实体死亡
        if (hasCriticalPartDestroyed(entityUuid)) {
            return false;
        }
        
        // 检查是否所有部位都被破坏
        boolean allDestroyed = parts.stream().allMatch(ClientBodyPart::isDestroyed);
        if (allDestroyed) {
            LOGGER.info("[ClientEntityBodySystem] All body parts destroyed: entityUuid={}", entityUuid);
            return false;
        }
        
        return true;
    }
    
    /**
     * 清理实体数据
     */
    public void clearEntity(UUID entityUuid) {
        List<ClientBodyPart> removed = entityBodyParts.remove(entityUuid);
        boneToBodyPartMap.remove(entityUuid);
        partIdToBodyPartMap.remove(entityUuid);
        
        LOGGER.info("[ClientEntityBodySystem] Cleared entity data: uuid={}, removedParts={}", 
                entityUuid, removed != null ? removed.size() : 0);
    }
    
    /**
     * 清理所有数据
     */
    public void clear() {
        int entityCount = entityBodyParts.size();
        entityBodyParts.clear();
        boneToBodyPartMap.clear();
        partIdToBodyPartMap.clear();
        LOGGER.info("[ClientEntityBodySystem] Cleared all data: entityCount={}", entityCount);
    }
}
