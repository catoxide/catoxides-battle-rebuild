package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IBodyPartConfig;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.ability.IBodyPartAbility;
import net.minecraft.world.damagesource.DamageSource;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 身体部位实现
 * 负责管理血量、伤害处理和骨骼关联
 * 包含多个BodyUnit，每个BodyUnit关联一个Bone
 */
public class BodyPart implements IBodyPart {
    
    // 基础属性
    private final String partName;
    private final IBodyPartConfig config;
    
    // BodyUnit列表（final，在构造时创建）
    private final List<BodyUnit> bodyUnits;
    
    // 骨骼到BodyUnit的映射（快速查找）
    private final Map<String, BodyUnit> boneToUnitMap;
    
    // 能力系统
    private final List<IBodyPartAbility> abilities;
    
    // 状态标志
    private boolean isAlive;
    
    // 构造函数
    public BodyPart(String partName, IBodyPartConfig config, List<BodyUnit> bodyUnits) {
        this.partName = partName;
        this.config = config;
        this.bodyUnits = new ArrayList<>(bodyUnits);
        this.boneToUnitMap = new ConcurrentHashMap<>();
        this.abilities = new ArrayList<>();
        this.isAlive = true;
        
        // 构建骨骼到BodyUnit的映射
        for (BodyUnit unit : this.bodyUnits) {
            this.boneToUnitMap.put(unit.getBoneName(), unit);
        }
        
        // TODO: 初始化能力系统
    }
    
    @Override
    public String getPartName() {
        return partName;
    }
    
    // ==================== BodyUnit管理 ====================
    
    /**
     * 获取所有BodyUnit
     */
    public List<BodyUnit> getBodyUnits() {
        return Collections.unmodifiableList(bodyUnits);
    }
    
    /**
     * 根据骨骼名称获取对应的BodyUnit
     */
    public BodyUnit getUnitByBoneName(String boneName) {
        return boneToUnitMap.get(boneName);
    }
    
    /**
     * 检查是否包含指定骨骼的BodyUnit
     */
    public boolean hasBone(String boneName) {
        return boneToUnitMap.containsKey(boneName);
    }
    
    // ==================== 血量管理 ====================
    
    @Override
    public float getCurrentHealth() {
        // 返回所有BodyUnit的总血量
        return bodyUnits.stream()
            .map(BodyUnit::getCurrentHealth)
            .reduce(0.0f, Float::sum);
    }
    
    @Override
    public float getMaxHealth() {
        // 返回所有BodyUnit的最大总血量
        return bodyUnits.stream()
            .map(BodyUnit::getMaxHealth)
            .reduce(0.0f, Float::sum);
    }
    
    // ==================== 伤害处理 ====================
    
    @Override
    public float receiveDamage(float damage, DamageSource source) {
        // TODO: 实现伤害接收逻辑
        // 1. 应用能力系统的预处理
        // 2. 计算护甲减免
        // 3. 应用伤害倍率
        // 4. 更新血量
        // 5. 检查致命条件
        return 0f;
    }
    
    /**
     * 接收伤害（从指定骨骼）
     * @param boneName 骨骼名称
     * @param rawDamage 原始伤害
     * @param damageType 伤害类型
     * @return 实际受到的伤害
     */
    public float receiveDamage(String boneName, float rawDamage, String damageType) {
        BodyUnit unit = getUnitByBoneName(boneName);
        if (unit == null) {
            // 没有对应的BodyUnit，不处理伤害
            return 0.0f;
        }
        
        // 将伤害传导到对应的BodyUnit（新架构：BodyUnit与Bone是1对1关系，无需传入boneName）
        return unit.receiveDamage(rawDamage, damageType);
    }
    
    @Override
    public void transmitToEntity(float damage) {
        // TODO: 实现伤害传导到实体血量的逻辑
        // 将所有BodyUnit的伤害传导到实体
        for (BodyUnit unit : bodyUnits) {
            float transmittedDamage = unit.calculateEntityTransmission(damage);
            // TODO: 将transmittedDamage传导到实体血量
        }
    }
    
    // ==================== 状态判断 ====================
    
    @Override
    public boolean isCritical() {
        return config.isCriticalZone();
    }
    
    @Override
    public boolean isFatal() {
        // 检查是否有任何BodyUnit致命
        return bodyUnits.stream().anyMatch(BodyUnit::isFatal);
    }
    
    @Override
    public boolean isAlive() {
        // 检查是否所有BodyUnit都已死亡
        return bodyUnits.stream().anyMatch(BodyUnit::isAlive);
    }
    
    @Override
    public void reset() {
        // 重置所有BodyUnit
        for (BodyUnit unit : bodyUnits) {
            unit.reset();
        }
        this.isAlive = true;
        // TODO: 重置能力系统状态
    }
    
    // ==================== 骨骼管理（兼容旧接口） ====================
    
    @Override
    public void addBone(String boneName, float transmissionCoefficient) {
        // 新架构中，骨骼通过BodyUnit添加
        // 这个方法可能不再需要，或者需要创建新的BodyUnit
        // TODO: 根据实际需求实现
    }
    
    @Override
    public void removeBone(String boneName) {
        // 新架构中，骨骼通过BodyUnit移除
        // 这个方法可能不再需要
        // TODO: 根据实际需求实现
    }
    
    @Override
    public Map<String, Float> getBoneTransmissionCoefficients() {
        // 返回所有BodyUnit的骨骼传导系数
        Map<String, Float> result = new HashMap<>();
        for (BodyUnit unit : bodyUnits) {
            result.put(unit.getBoneName(), unit.getTransmissionCoefficient());
        }
        return result;
    }
    
    // ==================== 内部辅助方法 ====================
    
    private void applyDamage(float damage) {
        // 新架构中，伤害通过BodyUnit处理
        // 这个方法可能不再需要
    }
    
    private float calculateArmorReduction(float damage) {
        // 新架构中，护甲通过BodyUnit计算
        // 这个方法可能不再需要
        return 0f;
    }
    
    private void triggerAbilities(float damage, DamageSource source) {
        // TODO: 触发能力系统
    }
    
    /**
     * Tick更新
     */
    public void tick(int deltaTick) {
        // 更新所有BodyUnit
        for (BodyUnit unit : bodyUnits) {
            unit.tick(deltaTick);
        }
    }
}