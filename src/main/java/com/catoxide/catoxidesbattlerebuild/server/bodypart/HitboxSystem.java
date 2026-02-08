package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Collections;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.factory.HitboxFactory;

/**
 * Hitbox系统
 * 管理所有实体的Hitbox，处理伤害传导和结算
 */
public class HitboxSystem {
    
    // 单例实例
    private static HitboxSystem instance;
    
    // 实体ID -> Hitbox列表
    private final Map<Long, List<IHitbox>> entityHitboxes;
    
    // Hitbox ID -> Hitbox
    private final Map<UUID, IHitbox> hitboxMap;
    
    // 实体ID -> 骨骼名称 -> Hitbox
    private final Map<Long, Map<String, IHitbox>> boneToHitboxMap;
    
    /**
     * 私有构造函数
     */
    private HitboxSystem() {
        this.entityHitboxes = new HashMap<>();
        this.hitboxMap = new HashMap<>();
        this.boneToHitboxMap = new HashMap<>();
    }
    
    /**
     * 获取单例实例
     */
    public static HitboxSystem getInstance() {
        if (instance == null) {
            instance = new HitboxSystem();
        }
        return instance;
    }
    
    // ==================== 实体注册管理 ====================
    
    /**
     * 注册实体
     */
    public void registerEntity(long entityId) {
        if (!entityHitboxes.containsKey(entityId)) {
            entityHitboxes.put(entityId, new ArrayList<>());
            boneToHitboxMap.put(entityId, new HashMap<>());
        }
    }
    
    /**
     * 注销实体
     */
    public void unregisterEntity(long entityId) {
        List<IHitbox> hitboxes = entityHitboxes.remove(entityId);
        if (hitboxes != null) {
            for (IHitbox hitbox : hitboxes) {
                hitboxMap.remove(hitbox.getId());
            }
        }
        boneToHitboxMap.remove(entityId);
    }
    
    /**
     * 检查实体是否已注册
     */
    public boolean isEntityRegistered(long entityId) {
        return entityHitboxes.containsKey(entityId);
    }
    
    /**
     * 获取所有已注册的实体ID
     */
    public List<Long> getRegisteredEntityIds() {
        return new ArrayList<>(entityHitboxes.keySet());
    }
    
    // ==================== Hitbox管理 ====================
    
    /**
     * 添加Hitbox到实体
     */
    public void addHitbox(long entityId, IHitbox hitbox) {
        // 确保实体已注册
        registerEntity(entityId);
        
        // 添加到实体Hitbox列表
        entityHitboxes.get(entityId).add(hitbox);
        
        // 添加到全局映射
        hitboxMap.put(hitbox.getId(), hitbox);
        
        // 建立骨骼到Hitbox的映射
        Map<String, IHitbox> boneMap = boneToHitboxMap.get(entityId);
        for (String boneName : hitbox.getBones().keySet()) {
            boneMap.put(boneName, hitbox);
        }
    }
    
    /**
     * 批量添加Hitbox
     */
    public void addHitboxes(long entityId, List<IHitbox> hitboxes) {
        for (IHitbox hitbox : hitboxes) {
            addHitbox(entityId, hitbox);
        }
    }
    
    /**
     * 移除Hitbox
     */
    public void removeHitbox(long entityId, UUID hitboxId) {
        List<IHitbox> hitboxes = entityHitboxes.get(entityId);
        if (hitboxes != null) {
            IHitbox hitbox = hitboxMap.get(hitboxId);
            if (hitbox != null) {
                hitboxes.remove(hitbox);
                hitboxMap.remove(hitboxId);
                
                // 移除骨骼映射
                Map<String, IHitbox> boneMap = boneToHitboxMap.get(entityId);
                for (String boneName : hitbox.getBones().keySet()) {
                    boneMap.remove(boneName);
                }
            }
        }
    }
    
    /**
     * 移除实体的所有Hitbox
     */
    public void removeAllHitboxes(long entityId) {
        List<IHitbox> hitboxes = entityHitboxes.get(entityId);
        if (hitboxes != null) {
            for (IHitbox hitbox : hitboxes) {
                hitboxMap.remove(hitbox.getId());
            }
            hitboxes.clear();
            boneToHitboxMap.get(entityId).clear();
        }
    }
    
    /**
     * 获取实体的所有Hitbox
     */
    public List<IHitbox> getEntityHitboxes(long entityId) {
        List<IHitbox> hitboxes = entityHitboxes.get(entityId);
        return hitboxes != null ? Collections.unmodifiableList(hitboxes) : Collections.emptyList();
    }
    
    /**
     * 根据ID获取Hitbox
     */
    public IHitbox getHitbox(UUID hitboxId) {
        return hitboxMap.get(hitboxId);
    }
    
    /**
     * 根据名称获取Hitbox
     */
    public IHitbox getHitboxByName(long entityId, String hitboxName) {
        List<IHitbox> hitboxes = entityHitboxes.get(entityId);
        if (hitboxes != null) {
            for (IHitbox hitbox : hitboxes) {
                if (hitbox.getName().equals(hitboxName)) {
                    return hitbox;
                }
            }
        }
        return null;
    }
    
    /**
     * 根据骨骼获取Hitbox
     */
    public IHitbox getHitboxByBone(long entityId, String boneName) {
        Map<String, IHitbox> boneMap = boneToHitboxMap.get(entityId);
        return boneMap != null ? boneMap.get(boneName) : null;
    }
    
    /**
     * 检查实体是否有指定名称的Hitbox
     */
    public boolean hasHitbox(long entityId, String hitboxName) {
        return getHitboxByName(entityId, hitboxName) != null;
    }
    
    /**
     * 检查实体的骨骼是否关联到Hitbox
     */
    public boolean hasBone(long entityId, String boneName) {
        Map<String, IHitbox> boneMap = boneToHitboxMap.get(entityId);
        return boneMap != null && boneMap.containsKey(boneName);
    }
    
    // ==================== 伤害处理 ====================
    
    /**
     * 处理骨骼受到的伤害
     * @param entityId 实体ID
     * @param boneName 骨骼名称
     * @param rawDamage 原始伤害
     * @param damageType 伤害类型
     * @return 实际受到的伤害
     */
    public float processBoneDamage(long entityId, String boneName, float rawDamage, String damageType) {
        // 查找关联的Hitbox
        IHitbox hitbox = getHitboxByBone(entityId, boneName);
        if (hitbox == null || !hitbox.isActive()) {
            // 没有关联的Hitbox，直接传导到实体
            return rawDamage;
        }
        
        // Hitbox接收伤害
        float hitboxDamage = hitbox.receiveDamage(boneName, rawDamage, damageType);
        
        // 计算传导到实体的伤害
        float entityDamage = hitbox.calculateEntityTransmission(hitboxDamage);
        
        return entityDamage;
    }
    
    /**
     * 处理Hitbox受到的伤害
     * @param hitboxId Hitbox ID
     * @param rawDamage 原始伤害
     * @param damageType 伤害类型
     * @return 实际受到的伤害
     */
    public float processHitboxDamage(UUID hitboxId, float rawDamage, String damageType) {
        IHitbox hitbox = hitboxMap.get(hitboxId);
        if (hitbox == null || !hitbox.isActive()) {
            return 0.0f;
        }
        
        float hitboxDamage = hitbox.receiveDamage(null, rawDamage, damageType);
        float entityDamage = hitbox.calculateEntityTransmission(hitboxDamage);
        
        return entityDamage;
    }
    
    /**
     * 计算实体的总伤害传导
     * @param entityId 实体ID
     * @param boneName 骨骼名称
     * @param rawDamage 原始伤害
     * @param damageType 伤害类型
     * @return 传导到实体的总伤害
     */
    public float calculateTotalEntityDamage(long entityId, String boneName, float rawDamage, String damageType) {
        return processBoneDamage(entityId, boneName, rawDamage, damageType);
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 检查实体是否存活（所有Hitbox都存活）
     */
    public boolean isEntityAlive(long entityId) {
        List<IHitbox> hitboxes = entityHitboxes.get(entityId);
        if (hitboxes == null || hitboxes.isEmpty()) {
            return true; // 没有Hitbox，认为存活
        }
        
        for (IHitbox hitbox : hitboxes) {
            if (!hitbox.isAlive()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 检查实体是否有致命Hitbox
     */
    public boolean hasFatalHitbox(long entityId) {
        List<IHitbox> hitboxes = entityHitboxes.get(entityId);
        if (hitboxes == null) {
            return false;
        }
        
        for (IHitbox hitbox : hitboxes) {
            if (hitbox.isFatal()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 获取实体的总血量
     */
    public float getTotalHealth(long entityId) {
        List<IHitbox> hitboxes = entityHitboxes.get(entityId);
        if (hitboxes == null || hitboxes.isEmpty()) {
            return 0.0f;
        }
        
        float total = 0.0f;
        for (IHitbox hitbox : hitboxes) {
            total += hitbox.getCurrentHealth();
        }
        return total;
    }
    
    /**
     * 获取实体的最大总血量
     */
    public float getMaxTotalHealth(long entityId) {
        List<IHitbox> hitboxes = entityHitboxes.get(entityId);
        if (hitboxes == null || hitboxes.isEmpty()) {
            return 0.0f;
        }
        
        float total = 0.0f;
        for (IHitbox hitbox : hitboxes) {
            total += hitbox.getMaxHealth();
        }
        return total;
    }
    
    // ==================== Tick更新 ====================
    
    /**
     * Tick更新
     */
    public void tick(int deltaTick) {
        for (List<IHitbox> hitboxes : entityHitboxes.values()) {
            for (IHitbox hitbox : hitboxes) {
                if (hitbox instanceof Hitbox) {
                    ((Hitbox) hitbox).tick(deltaTick);
                }
            }
        }
    }
    
    // ==================== 清理 ====================
    
    /**
     * 清空所有数据
     */
    public void clear() {
        entityHitboxes.clear();
        hitboxMap.clear();
        boneToHitboxMap.clear();
    }
}
