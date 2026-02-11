package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Collections;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.factory.BodyUnitFactory;

/**
 * BodyUnit系统
 * 管理所有实体的BodyUnit，处理伤害传导和结算
 */
public class BodyUnitSystem {
    
    // 单例实例
    private static BodyUnitSystem instance;
    
    // 实体ID -> BodyUnit列表
    private final Map<Long, List<IBodyUnit>> entityBodyUnits;
    
    // BodyUnit ID -> BodyUnit
    private final Map<UUID, IBodyUnit> bodyUnitMap;
    
    // 实体ID -> 骨骼名称 -> BodyUnit
    private final Map<Long, Map<String, IBodyUnit>> boneToBodyUnitMap;
    
    /**
     * 私有构造函数
     */
    private BodyUnitSystem() {
        this.entityBodyUnits = new HashMap<>();
        this.bodyUnitMap = new HashMap<>();
        this.boneToBodyUnitMap = new HashMap<>();
    }
    
    /**
     * 获取单例实例
     */
    public static BodyUnitSystem getInstance() {
        if (instance == null) {
            instance = new BodyUnitSystem();
        }
        return instance;
    }
    
    // ==================== 实体注册管理 ====================
    
    /**
     * 注册实体
     */
    public void registerEntity(long entityId) {
        if (!entityBodyUnits.containsKey(entityId)) {
            entityBodyUnits.put(entityId, new ArrayList<>());
            boneToBodyUnitMap.put(entityId, new HashMap<>());
        }
    }
    
    /**
     * 注销实体
     */
    public void unregisterEntity(long entityId) {
        List<IBodyUnit> bodyUnits = entityBodyUnits.remove(entityId);
        if (bodyUnits != null) {
            for (IBodyUnit bodyUnit : bodyUnits) {
                bodyUnitMap.remove(bodyUnit.getId());
            }
        }
        boneToBodyUnitMap.remove(entityId);
    }
    
    /**
     * 检查实体是否已注册
     */
    public boolean isEntityRegistered(long entityId) {
        return entityBodyUnits.containsKey(entityId);
    }
    
    /**
     * 获取所有已注册的实体ID
     */
    public List<Long> getRegisteredEntityIds() {
        return new ArrayList<>(entityBodyUnits.keySet());
    }
    
    // ==================== BodyUnit管理 ====================
    
    /**
     * 添加BodyUnit到实体
     */
    public void addBodyUnit(long entityId, IBodyUnit bodyUnit) {
        // 确保实体已注册
        registerEntity(entityId);
        
        // 添加到实体BodyUnit列表
        entityBodyUnits.get(entityId).add(bodyUnit);
        
        // 添加到全局映射
        bodyUnitMap.put(bodyUnit.getId(), bodyUnit);
        
        // 建立骨骼到BodyUnit的映射（新架构：1对1关系）
        Map<String, IBodyUnit> boneMap = boneToBodyUnitMap.get(entityId);
        String boneName = bodyUnit.getBoneName();
        if (boneName != null && !boneName.isEmpty()) {
            boneMap.put(boneName, bodyUnit);
        }
        
        // 关联BodyUnit到对应的BodyPart
        EntityBodySystem bodyPartSystem = EntityBodySystem.getInstance();
        if (bodyPartSystem != null) {
            IBodyPart bodyPart = bodyPartSystem.getBodyPartByBone(entityId, bodyUnit.getBoneName());
            if (bodyPart != null) {
                // 如果是BodyUnit实例，调用setBodyPart方法
                if (bodyUnit instanceof BodyUnit) {
                    ((BodyUnit) bodyUnit).setBodyPart(bodyPart);
                }
            }
        }
    }
    
    /**
     * 批量添加BodyUnit
     */
    public void addBodyUnits(long entityId, List<IBodyUnit> bodyUnits) {
        for (IBodyUnit bodyUnit : bodyUnits) {
            addBodyUnit(entityId, bodyUnit);
        }
    }
    
    /**
     * 移除BodyUnit
     */
    public void removeBodyUnit(long entityId, UUID bodyUnitId) {
        List<IBodyUnit> bodyUnits = entityBodyUnits.get(entityId);
        if (bodyUnits != null) {
            IBodyUnit bodyUnit = bodyUnitMap.get(bodyUnitId);
            if (bodyUnit != null) {
                bodyUnits.remove(bodyUnit);
                bodyUnitMap.remove(bodyUnitId);
                
                // 移除骨骼映射（新架构：1对1关系）
                Map<String, IBodyUnit> boneMap = boneToBodyUnitMap.get(entityId);
                String boneName = bodyUnit.getBoneName();
                if (boneName != null && !boneName.isEmpty()) {
                    boneMap.remove(boneName);
                }
            }
        }
    }
    
    /**
     * 移除实体的所有BodyUnit
     */
    public void removeAllBodyUnits(long entityId) {
        List<IBodyUnit> bodyUnits = entityBodyUnits.get(entityId);
        if (bodyUnits != null) {
            for (IBodyUnit bodyUnit : bodyUnits) {
                bodyUnitMap.remove(bodyUnit.getId());
            }
            bodyUnits.clear();
            boneToBodyUnitMap.get(entityId).clear();
        }
    }
    
    /**
     * 获取实体的所有BodyUnit
     */
    public List<IBodyUnit> getEntityBodyUnits(long entityId) {
        List<IBodyUnit> bodyUnits = entityBodyUnits.get(entityId);
        return bodyUnits != null ? Collections.unmodifiableList(bodyUnits) : Collections.emptyList();
    }
    
    /**
     * 根据ID获取BodyUnit
     */
    public IBodyUnit getBodyUnit(UUID bodyUnitId) {
        return bodyUnitMap.get(bodyUnitId);
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
     * 根据骨骼获取BodyUnit
     */
    public IBodyUnit getBodyUnitByBone(long entityId, String boneName) {
        Map<String, IBodyUnit> boneMap = boneToBodyUnitMap.get(entityId);
        return boneMap != null ? boneMap.get(boneName) : null;
    }
    
    /**
     * 检查实体是否有指定名称的BodyUnit
     */
    public boolean hasBodyUnit(long entityId, String bodyUnitName) {
        return getBodyUnitByName(entityId, bodyUnitName) != null;
    }
    
    /**
     * 检查实体的骨骼是否关联到BodyUnit
     */
    public boolean hasBone(long entityId, String boneName) {
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
        // 查找关联的BodyUnit
        IBodyUnit bodyUnit = getBodyUnitByBone(entityId, boneName);
        if (bodyUnit == null || !bodyUnit.isActive()) {
            // 没有关联的BodyUnit，直接传导到实体
            return rawDamage;
        }
        
        // BodyUnit接收伤害（传入boneName参数以匹配接口定义）
        float bodyUnitDamage = bodyUnit.receiveDamage(boneName, rawDamage, damageType);
        
        // 计算传导到实体的伤害
        float entityDamage = bodyUnit.calculateEntityTransmission(bodyUnitDamage);
        
        return entityDamage;
    }
    
    /**
     * 处理BodyUnit受到的伤害
     * @param bodyUnitId BodyUnit ID
     * @param rawDamage 原始伤害
     * @param damageType 伤害类型
     * @return 实际受到的伤害
     */
    public float processBodyUnitDamage(UUID bodyUnitId, float rawDamage, String damageType) {
        IBodyUnit bodyUnit = bodyUnitMap.get(bodyUnitId);
        if (bodyUnit == null || !bodyUnit.isActive()) {
            return 0.0f;
        }
        
        // BodyUnit接收伤害（传入boneName参数以匹配接口定义）
        String boneName = bodyUnit.getBoneName();
        float bodyUnitDamage = bodyUnit.receiveDamage(boneName, rawDamage, damageType);
        float entityDamage = bodyUnit.calculateEntityTransmission(bodyUnitDamage);
        
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
     * 检查实体是否存活（所有BodyUnit都存活）
     */
    public boolean isEntityAlive(long entityId) {
        List<IBodyUnit> bodyUnits = entityBodyUnits.get(entityId);
        if (bodyUnits == null || bodyUnits.isEmpty()) {
            return true; // 没有BodyUnit，认为存活
        }
        
        for (IBodyUnit bodyUnit : bodyUnits) {
            if (!bodyUnit.isAlive()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 检查实体是否有致命BodyUnit
     */
    public boolean hasFatalBodyUnit(long entityId) {
        List<IBodyUnit> bodyUnits = entityBodyUnits.get(entityId);
        if (bodyUnits == null) {
            return false;
        }
        
        for (IBodyUnit bodyUnit : bodyUnits) {
            if (bodyUnit.isFatal()) {
                return true;
            }
        }
        return false;
    }
    
    // ==================== Tick更新 ====================
    
    /**
     * Tick更新
     */
    public void tick(int deltaTick) {
        for (List<IBodyUnit> bodyUnits : entityBodyUnits.values()) {
            for (IBodyUnit bodyUnit : bodyUnits) {
                if (bodyUnit instanceof BodyUnit) {
                    ((BodyUnit) bodyUnit).tick(deltaTick);
                }
            }
        }
    }
    
    // ==================== 清理 ====================
    
    /**
     * 清空所有数据
     */
    public void clear() {
        entityBodyUnits.clear();
        bodyUnitMap.clear();
        boneToBodyUnitMap.clear();
    }
}