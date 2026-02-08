package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.factory.HitboxFactory;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IHitboxConfig;

/**
 * 实体骨骼Hitbox系统（游戏性逻辑层）
 * 负责管理实体的Hitbox，处理伤害结算和传导
 * 与几何层分离：几何层负责碰撞检测，本层负责伤害处理
 */
public class EntityBoneHitboxSystem {
    
    private static final EntityBoneHitboxSystem INSTANCE = new EntityBoneHitboxSystem();
    
    // 实体ID -> Hitbox列表映射
    private final Map<Long, List<IHitbox>> entityHitboxes = new ConcurrentHashMap<>();
    
    // 实体ID -> 骨骼名称 -> Hitbox映射
    private final Map<Long, Map<String, IHitbox>> boneToHitboxMap = new ConcurrentHashMap<>();
    
    // 活跃实体列表
    private final Set<Long> activeEntities = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    private final HitboxSystem hitboxSystem = HitboxSystem.getInstance();
    
    private EntityBoneHitboxSystem() {}
    
    public static EntityBoneHitboxSystem getInstance() {
        return INSTANCE;
    }
    
    // ==================== 实体注册管理 ====================
    
    /**
     * 注册实体
     * @param entityId 实体ID
     * @param boneHitboxMappings 骨骼名称 -> Hitbox名称的映射
     */
    public void registerEntity(long entityId, Map<String, String> boneHitboxMappings) {
        if (boneHitboxMappings == null || boneHitboxMappings.isEmpty()) {
            return;
        }
        
        // 注册到HitboxSystem
        hitboxSystem.registerEntity(entityId);
        
        // 创建Hitbox
        List<IHitbox> hitboxes = new ArrayList<>();
        Map<String, IHitbox> boneMap = new HashMap<>();
        
        for (Map.Entry<String, String> entry : boneHitboxMappings.entrySet()) {
            String boneName = entry.getKey();
            String hitboxName = entry.getValue();
            
            // 从工厂创建Hitbox
            IHitbox hitbox = HitboxFactory.getInstance().createFromTemplate(hitboxName, entityId);
            if (hitbox != null) {
                // 添加骨骼关联
                hitbox.addBone(boneName, 1.0f); // 默认传导系数
                
                hitboxes.add(hitbox);
                boneMap.put(boneName, hitbox);
            }
        }
        
        entityHitboxes.put(entityId, hitboxes);
        boneToHitboxMap.put(entityId, boneMap);
        activeEntities.add(entityId);
    }
    
    /**
     * 注册实体（使用Hitbox配置）
     * @param entityId 实体ID
     * @param boneHitboxConfigs 骨骼名称 -> Hitbox配置的映射
     */
    public void registerEntityWithConfigs(long entityId, Map<String, IHitboxConfig> boneHitboxConfigs) {
        if (boneHitboxConfigs == null || boneHitboxConfigs.isEmpty()) {
            return;
        }
        
        hitboxSystem.registerEntity(entityId);
        
        List<IHitbox> hitboxes = new ArrayList<>();
        Map<String, IHitbox> boneMap = new HashMap<>();
        
        for (Map.Entry<String, IHitboxConfig> entry : boneHitboxConfigs.entrySet()) {
            String boneName = entry.getKey();
            IHitboxConfig config = entry.getValue();
            
            // 从配置创建Hitbox
            IHitbox hitbox = HitboxFactory.getInstance().createFromConfig(config, entityId);
            if (hitbox != null) {
                hitbox.addBone(boneName, 1.0f);
                
                hitboxes.add(hitbox);
                boneMap.put(boneName, hitbox);
            }
        }
        
        entityHitboxes.put(entityId, hitboxes);
        boneToHitboxMap.put(entityId, boneMap);
        activeEntities.add(entityId);
    }
    
    /**
     * 注册实体（使用现有Hitbox）
     * @param entityId 实体ID
     * @param boneHitboxMappings 骨骼名称 -> Hitbox的映射
     */
    public void registerEntityWithHitboxes(long entityId, Map<String, IHitbox> boneHitboxMappings) {
        if (boneHitboxMappings == null || boneHitboxMappings.isEmpty()) {
            return;
        }
        
        hitboxSystem.registerEntity(entityId);
        
        List<IHitbox> hitboxes = new ArrayList<>();
        Map<String, IHitbox> boneMap = new HashMap<>();
        
        for (Map.Entry<String, IHitbox> entry : boneHitboxMappings.entrySet()) {
            String boneName = entry.getKey();
            IHitbox hitbox = entry.getValue();
            
            if (hitbox != null) {
                hitbox.addBone(boneName, 1.0f);
                
                hitboxes.add(hitbox);
                boneMap.put(boneName, hitbox);
            }
        }
        
        entityHitboxes.put(entityId, hitboxes);
        boneToHitboxMap.put(entityId, boneMap);
        activeEntities.add(entityId);
    }
    
    /**
     * 注销实体
     */
    public void unregisterEntity(long entityId) {
        hitboxSystem.unregisterEntity(entityId);
        entityHitboxes.remove(entityId);
        boneToHitboxMap.remove(entityId);
        activeEntities.remove(entityId);
    }
    
    /**
     * 检查实体是否已注册
     */
    public boolean isEntityRegistered(long entityId) {
        return activeEntities.contains(entityId);
    }
    
    /**
     * 获取所有活跃实体ID
     */
    public Set<Long> getActiveEntities() {
        return Collections.unmodifiableSet(activeEntities);
    }
    
    // ==================== Hitbox管理 ====================
    
    /**
     * 获取实体的所有Hitbox
     */
    public List<IHitbox> getEntityHitboxes(long entityId) {
        List<IHitbox> hitboxes = entityHitboxes.get(entityId);
        return hitboxes != null ? Collections.unmodifiableList(hitboxes) : Collections.emptyList();
    }
    
    /**
     * 根据骨骼获取Hitbox
     */
    public IHitbox getHitboxByBone(long entityId, String boneName) {
        Map<String, IHitbox> boneMap = boneToHitboxMap.get(entityId);
        return boneMap != null ? boneMap.get(boneName) : null;
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
     * 检查骨骼是否关联到Hitbox
     */
    public boolean hasBoneHitbox(long entityId, String boneName) {
        Map<String, IHitbox> boneMap = boneToHitboxMap.get(entityId);
        return boneMap != null && boneMap.containsKey(boneName);
    }
    
    /**
     * 添加骨骼到Hitbox
     * @param entityId 实体ID
     * @param boneName 骨骼名称
     * @param hitboxName Hitbox名称
     * @param transmissionCoefficient 传导系数
     */
    public void addBoneToHitbox(long entityId, String boneName, String hitboxName, float transmissionCoefficient) {
        IHitbox hitbox = getHitboxByName(entityId, hitboxName);
        if (hitbox != null) {
            hitbox.addBone(boneName, transmissionCoefficient);
            boneToHitboxMap.get(entityId).put(boneName, hitbox);
        }
    }
    
    /**
     * 移除骨骼关联
     */
    public void removeBoneFromHitbox(long entityId, String boneName) {
        Map<String, IHitbox> boneMap = boneToHitboxMap.get(entityId);
        if (boneMap != null) {
            IHitbox hitbox = boneMap.remove(boneName);
            if (hitbox != null) {
                hitbox.removeBone(boneName);
            }
        }
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
        return hitboxSystem.processBoneDamage(entityId, boneName, rawDamage, damageType);
    }
    
    /**
     * 处理Hitbox受到的伤害
     * @param hitboxId Hitbox ID
     * @param rawDamage 原始伤害
     * @param damageType 伤害类型
     * @return 实际受到的伤害
     */
    public float processHitboxDamage(java.util.UUID hitboxId, float rawDamage, String damageType) {
        return hitboxSystem.processHitboxDamage(hitboxId, rawDamage, damageType);
    }
    
    /**
     * 计算实体的总伤害传导
     */
    public float calculateTotalEntityDamage(long entityId, String boneName, float rawDamage, String damageType) {
        return hitboxSystem.calculateTotalEntityDamage(entityId, boneName, rawDamage, damageType);
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 检查实体是否存活
     */
    public boolean isEntityAlive(long entityId) {
        return hitboxSystem.isEntityAlive(entityId);
    }
    
    /**
     * 检查实体是否有致命Hitbox
     */
    public boolean hasFatalHitbox(long entityId) {
        return hitboxSystem.hasFatalHitbox(entityId);
    }
    
    /**
     * 获取实体的总血量
     */
    public float getTotalHealth(long entityId) {
        return hitboxSystem.getTotalHealth(entityId);
    }
    
    /**
     * 获取实体的最大总血量
     */
    public float getMaxTotalHealth(long entityId) {
        return hitboxSystem.getMaxTotalHealth(entityId);
    }
    
    /**
     * 获取实体的血量百分比
     */
    public float getHealthPercentage(long entityId) {
        float current = getTotalHealth(entityId);
        float max = getMaxTotalHealth(entityId);
        return max > 0 ? (current / max) * 100.0f : 0.0f;
    }
    
    // ==================== Tick更新 ====================
    
    /**
     * Tick更新
     */
    public void tick(int deltaTick) {
        hitboxSystem.tick(deltaTick);
    }
    
    // ==================== 击中事件 ====================
    
    /**
     * 击中结果记录
     */
    public record EntityHitResult(
        long entityId,
        String boneName,
        IHitbox hitbox,
        float actualDamage,
        boolean isFatal
    ) {
        /**
         * 创建击中结果
         */
        public static EntityHitResult create(long entityId, String boneName, IHitbox hitbox, float actualDamage) {
            boolean isFatal = hitbox != null && hitbox.isFatal() && !hitbox.isAlive();
            return new EntityHitResult(entityId, boneName, hitbox, actualDamage, isFatal);
        }
    }
    
    /**
     * 处理实体击中事件
     * @param entityId 实体ID
     * @param boneName 骨骼名称
     * @param rawDamage 原始伤害
     * @param damageType 伤害类型
     * @return 击中结果
     */
    public EntityHitResult processEntityHit(long entityId, String boneName, float rawDamage, String damageType) {
        IHitbox hitbox = getHitboxByBone(entityId, boneName);
        if (hitbox == null || !hitbox.isActive()) {
            // 没有关联的Hitbox，直接返回
            return EntityHitResult.create(entityId, boneName, null, rawDamage);
        }
        
        // 处理伤害
        float actualDamage = processBoneDamage(entityId, boneName, rawDamage, damageType);
        
        // 创建击中结果
        return EntityHitResult.create(entityId, boneName, hitbox, actualDamage);
    }
    
    // ==================== 清理 ====================
    
    /**
     * 清空所有数据
     */
    public void clear() {
        hitboxSystem.clear();
        entityHitboxes.clear();
        boneToHitboxMap.clear();
        activeEntities.clear();
    }
}
