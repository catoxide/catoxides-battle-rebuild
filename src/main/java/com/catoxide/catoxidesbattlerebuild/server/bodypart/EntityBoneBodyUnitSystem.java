package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.factory.BodyUnitFactory;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IBodyUnitConfig;

/**
 * 实体骨骼BodyUnit系统（游戏性逻辑层）
 * 负责管理实体的BodyPart和BodyUnit，处理伤害结算和传导
 * 新架构：Entity -> BodyPart -> BodyUnit -> Bone
 * 与几何层分离：几何层负责碰撞检测，本层负责伤害处理
 */
public class EntityBoneBodyUnitSystem {
    
    private static final EntityBoneBodyUnitSystem INSTANCE = new EntityBoneBodyUnitSystem();
    
    // 实体ID -> BodyPart列表映射（新架构）
    private final Map<Long, List<IBodyPart>> entityBodyParts = new ConcurrentHashMap<>();
    
    // 实体ID -> 部位名称 -> BodyPart映射（新架构）
    private final Map<Long, Map<String, IBodyPart>> entityPartMap = new ConcurrentHashMap<>();
    
    // 实体ID -> BodyUnit列表映射（保留用于兼容）
    private final Map<Long, List<IBodyUnit>> entityBodyUnits = new ConcurrentHashMap<>();
    
    // 实体ID -> 骨骼名称 -> BodyUnit映射（保留用于兼容）
    private final Map<Long, Map<String, IBodyUnit>> boneToBodyUnitMap = new ConcurrentHashMap<>();
    
    // 活跃实体列表
    private final Set<Long> activeEntities = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    private final BodyUnitSystem bodyUnitSystem = BodyUnitSystem.getInstance();
    
    private EntityBoneBodyUnitSystem() {}
    
    public static EntityBoneBodyUnitSystem getInstance() {
        return INSTANCE;
    }
    
    // ==================== 实体注册管理（新架构） ====================
    
    /**
     * 注册实体（使用BodyPart列表）
     * @param entityId 实体ID
     * @param bodyParts BodyPart列表
     */
    public void registerEntityWithBodyParts(long entityId, List<IBodyPart> bodyParts) {
        if (bodyParts == null || bodyParts.isEmpty()) {
            return;
        }
        
        // 注册到BodyUnitSystem
        bodyUnitSystem.registerEntity(entityId);
        
        // 存储BodyPart列表
        List<IBodyPart> partsList = new ArrayList<>(bodyParts);
        entityBodyParts.put(entityId, partsList);
        
        // 构建部位名称映射
        Map<String, IBodyPart> partMap = new HashMap<>();
        for (IBodyPart part : partsList) {
            partMap.put(part.getPartName(), part);
        }
        entityPartMap.put(entityId, partMap);
        
        // 收集所有BodyUnit（用于兼容）
        List<IBodyUnit> allUnits = new ArrayList<>();
        Map<String, IBodyUnit> boneMap = new HashMap<>();
        
        for (IBodyPart part : partsList) {
            List<BodyUnit> units = part.getBodyUnits();
            for (BodyUnit unit : units) {
                allUnits.add(unit);
                boneMap.put(unit.getBoneName(), unit);
            }
        }
        
        entityBodyUnits.put(entityId, allUnits);
        boneToBodyUnitMap.put(entityId, boneMap);
        activeEntities.add(entityId);
    }
    
    /**
     * 注册实体（使用BodyPart映射）
     * @param entityId 实体ID
     * @param bodyPartMap 部位名称 -> BodyPart的映射
     */
    public void registerEntityWithBodyPartMap(long entityId, Map<String, IBodyPart> bodyPartMap) {
        if (bodyPartMap == null || bodyPartMap.isEmpty()) {
            return;
        }
        
        List<IBodyPart> partsList = new ArrayList<>(bodyPartMap.values());
        registerEntityWithBodyParts(entityId, partsList);
    }
    
    /**
     * 注销实体
     */
    public void unregisterEntity(long entityId) {
        bodyUnitSystem.unregisterEntity(entityId);
        entityBodyParts.remove(entityId);
        entityPartMap.remove(entityId);
        entityBodyUnits.remove(entityId);
        boneToBodyUnitMap.remove(entityId);
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
    
    // ==================== BodyPart管理（新架构） ====================
    
    /**
     * 获取实体的所有BodyPart
     */
    public List<IBodyPart> getEntityBodyParts(long entityId) {
        List<IBodyPart> parts = entityBodyParts.get(entityId);
        return parts != null ? Collections.unmodifiableList(parts) : Collections.emptyList();
    }
    
    /**
     * 根据名称获取BodyPart
     */
    public IBodyPart getBodyPartByName(long entityId, String partName) {
        Map<String, IBodyPart> partMap = entityPartMap.get(entityId);
        return partMap != null ? partMap.get(partName) : null;
    }
    
    /**
     * 根据骨骼获取所属的BodyPart
     */
    public IBodyPart getBodyPartByBone(long entityId, String boneName) {
        List<IBodyPart> parts = entityBodyParts.get(entityId);
        if (parts != null) {
            for (IBodyPart part : parts) {
                if (part.getUnitByBoneName(boneName) != null) {
                    return part;
                }
            }
        }
        return null;
    }
    
    // ==================== BodyUnit管理 ====================
    
    /**
     * 获取实体的所有BodyUnit
     */
    public List<IBodyUnit> getEntityBodyUnits(long entityId) {
        List<IBodyUnit> bodyUnits = entityBodyUnits.get(entityId);
        return bodyUnits != null ? Collections.unmodifiableList(bodyUnits) : Collections.emptyList();
    }
    
    /**
     * 根据骨骼获取BodyUnit
     */
    public IBodyUnit getBodyUnitByBone(long entityId, String boneName) {
        Map<String, IBodyUnit> boneMap = boneToBodyUnitMap.get(entityId);
        return boneMap != null ? boneMap.get(boneName) : null;
    }
    
    /**
     * 根据名称获取BodyUnit
     */
    public IBodyUnit getBodyUnitByName(long entityId, String bodyUnitName) {
        List<IBodyUnit> bodyUnits = entityBodyUnits.get(entityId);
        if (bodyUnits != null) {
            for (IBodyUnit bodyUnit : bodyUnits) {
                if (bodyUnit.getName().equals(bodyUnitName)) {
                    return bodyUnit;
                }
            }
        }
        return null;
    }
    
    /**
     * 检查骨骼是否关联到BodyUnit
     */
    public boolean hasBoneBodyUnit(long entityId, String boneName) {
        Map<String, IBodyUnit> boneMap = boneToBodyUnitMap.get(entityId);
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
        return bodyUnitSystem.processBoneDamage(entityId, boneName, rawDamage, damageType);
    }
    
    /**
     * 处理BodyUnit受到的伤害
     * @param bodyUnitId BodyUnit ID
     * @param rawDamage 原始伤害
     * @param damageType 伤害类型
     * @return 实际受到的伤害
     */
    public float processBodyUnitDamage(java.util.UUID bodyUnitId, float rawDamage, String damageType) {
        return bodyUnitSystem.processBodyUnitDamage(bodyUnitId, rawDamage, damageType);
    }
    
    /**
     * 计算实体的总伤害传导
     */
    public float calculateTotalEntityDamage(long entityId, String boneName, float rawDamage, String damageType) {
        return bodyUnitSystem.calculateTotalEntityDamage(entityId, boneName, rawDamage, damageType);
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 检查实体是否存活
     */
    public boolean isEntityAlive(long entityId) {
        return bodyUnitSystem.isEntityAlive(entityId);
    }
    
    /**
     * 检查实体是否有致命BodyUnit
     */
    public boolean hasFatalBodyUnit(long entityId) {
        return bodyUnitSystem.hasFatalBodyUnit(entityId);
    }
    
    /**
     * 获取实体的总血量
     */
    public float getTotalHealth(long entityId) {
        EntityBodySystem bodyPartSystem = EntityBodySystem.getInstance();
        if (bodyPartSystem != null) {
            return bodyPartSystem.getTotalHealth(entityId);
        }
        return 0.0f;
    }
    
    /**
     * 获取实体的最大总血量
     */
    public float getMaxTotalHealth(long entityId) {
        EntityBodySystem bodyPartSystem = EntityBodySystem.getInstance();
        if (bodyPartSystem != null) {
            return bodyPartSystem.getMaxTotalHealth(entityId);
        }
        return 0.0f;
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
        bodyUnitSystem.tick(deltaTick);
    }
    
    // ==================== 击中事件 ====================
    
    /**
     * 击中结果记录
     */
    public record EntityHitResult(
        long entityId,
        String boneName,
        IBodyPart bodyPart,
        IBodyUnit bodyUnit,
        float actualDamage,
        boolean isFatal
    ) {
        /**
         * 创建击中结果（新架构）
         */
        public static EntityHitResult create(long entityId, String boneName, IBodyPart bodyPart, 
                                               IBodyUnit bodyUnit, float actualDamage) {
            boolean isFatal = bodyUnit != null && bodyUnit.isFatal() && !bodyUnit.isAlive();
            return new EntityHitResult(entityId, boneName, bodyPart, bodyUnit, actualDamage, isFatal);
        }
        
        /**
         * 创建击中结果（兼容旧接口）
         */
        public static EntityHitResult createLegacy(long entityId, String boneName, IBodyUnit bodyUnit, float actualDamage) {
            boolean isFatal = bodyUnit != null && bodyUnit.isFatal() && !bodyUnit.isAlive();
            return new EntityHitResult(entityId, boneName, null, bodyUnit, actualDamage, isFatal);
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
        // 新架构：先尝试通过BodyPart获取BodyUnit
        IBodyPart bodyPart = getBodyPartByBone(entityId, boneName);
        IBodyUnit bodyUnit = null;
        
        if (bodyPart != null) {
            bodyUnit = bodyPart.getUnitByBoneName(boneName);
        }
        
        // 如果没有找到，尝试旧方式
        if (bodyUnit == null) {
            bodyUnit = getBodyUnitByBone(entityId, boneName);
        }
        
        if (bodyUnit == null || !bodyUnit.isActive()) {
            // 没有关联的BodyUnit，直接返回
            return EntityHitResult.createLegacy(entityId, boneName, null, rawDamage);
        }
        
        // 处理伤害
        float actualDamage = processBoneDamage(entityId, boneName, rawDamage, damageType);
        
        // 创建击中结果
        if (bodyPart != null) {
            return EntityHitResult.create(entityId, boneName, bodyPart, bodyUnit, actualDamage);
        } else {
            return EntityHitResult.createLegacy(entityId, boneName, bodyUnit, actualDamage);
        }
    }
    
    // ==================== 清理 ====================
    
    /**
     * 清空所有数据
     */
    public void clear() {
        bodyUnitSystem.clear();
        entityBodyParts.clear();
        entityPartMap.clear();
        entityBodyUnits.clear();
        boneToBodyUnitMap.clear();
        activeEntities.clear();
    }
}