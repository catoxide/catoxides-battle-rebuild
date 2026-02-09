package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IBodyPartConfig;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 实体身体部位系统
 * 管理实体的所有身体部位和伤害处理
 */
public class EntityBodySystem {
    
    private static final EntityBodySystem INSTANCE = new EntityBodySystem();
    
    // 实体ID -> 部位列表
    private final Map<Long, Map<String, IBodyPart>> entityBodyParts;
    
    // 实体ID -> 骨骼名称 -> 部位名称（确保每个骨骼只属于一个部位）
    private final Map<Long, Map<String, String>> entityBoneToPartMap;
    
    // 活跃实体列表
    private final Set<Long> activeEntities;
    
    private EntityBodySystem() {
        this.entityBodyParts = new HashMap<>();
        this.entityBoneToPartMap = new HashMap<>();
        this.activeEntities = new HashSet<>();
    }
    
    public static EntityBodySystem getInstance() {
        return INSTANCE;
    }
    
    /**
     * 注册实体及其身体部位
     * @param entityId 实体ID
     * @param bodyParts 部位映射（部位名称 -> 部位实例）
     */
    public void registerEntity(long entityId, Map<String, IBodyPart> bodyParts) {
        entityBodyParts.put(entityId, bodyParts);
        activeEntities.add(entityId);
        
        // 建立骨骼到部位的映射
        Map<String, String> boneToPartMap = new HashMap<>();
        for (Map.Entry<String, IBodyPart> entry : bodyParts.entrySet()) {
            String partName = entry.getKey();
            IBodyPart part = entry.getValue();
            
            // 将该部位的所有骨骼映射到该部位
            Map<String, Float> boneMap = part.getBoneTransmissionCoefficients();
            for (String boneName : boneMap.keySet()) {
                // 验证：检查骨骼是否已被分配
                if (boneToPartMap.containsKey(boneName)) {
                    String existingPart = boneToPartMap.get(boneName);
                    throw new IllegalStateException(
                        String.format("骨骼 '%s' 已被分配到部位 '%s'，不能再次分配到部位 '%s'",
                            boneName, existingPart, partName)
                    );
                }
                boneToPartMap.put(boneName, partName);
            }
        }
        entityBoneToPartMap.put(entityId, boneToPartMap);
    }
    
    /**
     * 注销实体
     * @param entityId 实体ID
     */
    public void unregisterEntity(long entityId) {
        entityBodyParts.remove(entityId);
        entityBoneToPartMap.remove(entityId);
        activeEntities.remove(entityId);
    }
    
    /**
     * 获取实体的所有部位
     * @param entityId 实体ID
     * @return 部位映射
     */
    public Map<String, IBodyPart> getBodyParts(long entityId) {
        return entityBodyParts.get(entityId);
    }
    
    /**
     * 获取实体的特定部位
     * @param entityId 实体ID
     * @param partName 部位名称
     * @return 部位实例，如果不存在则返回null
     */
    public IBodyPart getBodyPart(long entityId, String partName) {
        Map<String, IBodyPart> parts = entityBodyParts.get(entityId);
        if (parts != null) {
            return parts.get(partName);
        }
        return null;
    }
    
    /**
     * 根据骨骼名称获取对应的身体部位
     * @param entityId 实体ID
     * @param boneName 骨骼名称
     * @return 对应的身体部位，如果骨骼不存在则返回null
     */
    public IBodyPart getBodyPartByBone(long entityId, String boneName) {
        Map<String, String> boneToPartMap = entityBoneToPartMap.get(entityId);
        if (boneToPartMap == null) {
            return null;
        }
        
        String partName = boneToPartMap.get(boneName);
        if (partName == null) {
            return null;
        }
        
        return getBodyPart(entityId, partName);
    }
    
    /**
     * 处理实体受到的伤害
     * @param entityId 实体ID
     * @param boneName 受击骨骼
     * @param hitPoint 击中点
     * @param incomingDamage 传入伤害
     * @param source 伤害来源
     * @return 实际受到的伤害
     */
    public float processDamage(long entityId, String boneName, Vec3 hitPoint, float incomingDamage, DamageSource source) {
        // 1. 根据骨骼找到对应的部位
        IBodyPart targetPart = getBodyPartByBone(entityId, boneName);
        if (targetPart == null) {
            // 如果找不到对应部位，直接返回0或默认处理
            return 0f;
        }
        
        // 2. 获取传导系数
        Map<String, Float> boneCoefficients = targetPart.getBoneTransmissionCoefficients();
        Float transmissionCoefficient = boneCoefficients.get(boneName);
        if (transmissionCoefficient == null) {
            return 0f;
        }
        
        // 3. 传导伤害到部位
        float transmittedDamage = incomingDamage * transmissionCoefficient;
        
        // 4. 部位处理伤害
        float actualDamage = targetPart.receiveDamage(transmittedDamage, source);
        
        // 5. 传导到实体血量
        targetPart.transmitToEntity(actualDamage);
        
        return actualDamage;
    }
    
    /**
     * 检查实体是否存活
     * @param entityId 实体ID
     * @return 是否存活
     */
    public boolean isEntityAlive(long entityId) {
        Map<String, IBodyPart> parts = entityBodyParts.get(entityId);
        if (parts == null) {
            return false;
        }
        
        // TODO: 实现存活检查逻辑
        // 可能需要检查关键部位是否存活
        return true;
    }
    
    /**
     * 重置实体的所有部位
     * @param entityId 实体ID
     */
    public void resetEntity(long entityId) {
        Map<String, IBodyPart> parts = entityBodyParts.get(entityId);
        if (parts != null) {
            for (IBodyPart part : parts.values()) {
                part.reset();
            }
        }
    }
    
    /**
     * 获取所有活跃实体ID
     * @return 活跃实体ID集合
     */
    public Set<Long> getActiveEntities() {
        return new HashSet<>(activeEntities);
    }
    
    /**
     * 检查实体是否已注册
     * @param entityId 实体ID
     * @return 是否已注册
     */
    public boolean isEntityRegistered(long entityId) {
        return activeEntities.contains(entityId);
    }
}




