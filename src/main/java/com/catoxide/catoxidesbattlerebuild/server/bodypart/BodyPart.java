package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import net.minecraft.world.damagesource.DamageSource;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IBodyPartConfig;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.ability.IBodyPartAbility;

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
    
    // 血量管理
    private float currentHealth;
    private float maxHealth;
    
    // 构造函数
    public BodyPart(String partName, IBodyPartConfig config, List<BodyUnit> bodyUnits) {
        this.partName = partName;
        this.config = config;
        this.bodyUnits = bodyUnits;
        this.boneToUnitMap = new ConcurrentHashMap<>();
        this.abilities = new ArrayList<>();
        this.isAlive = true;
        
        // 初始化血量
        this.maxHealth = config != null ? config.getBaseHealth() : 100.0f;
        this.currentHealth = this.maxHealth;
        
        // 构建骨骼到BodyUnit的映射
        for (BodyUnit unit : this.bodyUnits) {
            this.boneToUnitMap.put(unit.getBoneName(), unit);
        }
        
        // TODO: 初始化能力系统
    }
    
    /**
     * 构造函数（直接创建）
     */
    public BodyPart(String partName, List<BodyUnit> bodyUnits) {
        this(partName, null, bodyUnits);
        
        // 计算默认血量
        this.maxHealth = 100.0f;
        this.currentHealth = this.maxHealth;
    }
    
    // ==================== 基础方法 ====================
    
    @Override
    public String getPartName() {
        return partName;
    }
    
    // ==================== 血量管理 ====================
    
    @Override
    public float getCurrentHealth() {
        return currentHealth;
    }
    
    @Override
    public float getMaxHealth() {
        return maxHealth;
    }
    
    @Override
    public void setCurrentHealth(float health) {
        this.currentHealth = Math.max(0, Math.min(health, maxHealth));
        
        // 更新存活状态
        this.isAlive = this.currentHealth > 0;
    }
    
    @Override
    public void setMaxHealth(float maxHealth) {
        this.maxHealth = Math.max(0, maxHealth);
        
        // 确保当前血量不超过最大血量
        if (currentHealth > this.maxHealth) {
            currentHealth = this.maxHealth;
        }
        
        // 更新存活状态
        this.isAlive = this.currentHealth > 0;
    }
    
    // ==================== 伤害处理 ====================
    
    @Override
    public float receiveDamage(float damage, DamageSource source) {
        if (!isAlive) {
            return 0.0f;
        }
        
        // 计算实际受到的伤害
        float actualDamage = damage;
        
        // 分配伤害到各个BodyUnit
        for (BodyUnit unit : bodyUnits) {
            if (unit.isActive()) {
                // 计算该BodyUnit应承担的伤害比例
                // TODO: 实现基于BodyUnit属性的伤害分配逻辑
                float unitDamage = actualDamage * (1.0f / bodyUnits.size());
                unit.receiveDamage(unit.getBoneName(), unitDamage, "physical");
            }
        }
        
        // 扣除部位血量
        float oldHealth = currentHealth;
        currentHealth = Math.max(0, currentHealth - actualDamage);
        
        // 更新存活状态
        this.isAlive = this.currentHealth > 0;
        
        return actualDamage;
    }
    
    @Override
    public float receiveDamageByBone(String boneName, float damage, DamageSource source) {
        if (!isAlive) {
            return 0.0f;
        }
        
        // 查找对应的BodyUnit
        BodyUnit unit = boneToUnitMap.get(boneName);
        if (unit == null || !unit.isActive()) {
            return 0.0f;
        }
        
        // 处理伤害
        float actualDamage = unit.receiveDamage(boneName, damage, "physical");
        
        // 扣除部位血量
        float oldHealth = currentHealth;
        currentHealth = Math.max(0, currentHealth - actualDamage);
        
        // 更新存活状态
        this.isAlive = this.currentHealth > 0;
        
        return actualDamage;
    }
    
    @Override
    public void transmitToEntity(float damage) {
        // TODO: 实现伤害传导到实体的逻辑
    }
    
    // ==================== 区域标记 ====================
    
    @Override
    public boolean isCritical() {
        for (BodyUnit unit : bodyUnits) {
            if (unit.isCritical()) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    public boolean isFatal() {
        for (BodyUnit unit : bodyUnits) {
            if (unit.isFatal()) {
                return true;
            }
        }
        return false;
    }
    
    // ==================== BodyUnit管理 ====================
    
    @Override
    public List<BodyUnit> getBodyUnits() {
        return bodyUnits;
    }
    
    @Override
    public BodyUnit getUnitByBoneName(String boneName) {
        return boneToUnitMap.get(boneName);
    }
    
    /**
     * 检查是否包含指定骨骼的BodyUnit
     */
    public boolean hasBone(String boneName) {
        return boneToUnitMap.containsKey(boneName);
    }
    
    // ==================== 状态管理 ====================
    
    @Override
    public boolean isAlive() {
        return isAlive;
    }
    
    @Override
    public void reset() {
        // 重置部位状态
        this.currentHealth = maxHealth;
        this.isAlive = true;
        
        // 重置所有BodyUnit
        for (BodyUnit unit : bodyUnits) {
            unit.reset();
        }
        
        // 重置能力
        for (IBodyPartAbility ability : abilities) {
            ability.reset();
        }
    }
    
    // ==================== 能力系统 ====================
    
    /**
     * 添加能力
     */
    public void addAbility(IBodyPartAbility ability) {
        if (!abilities.contains(ability)) {
            ability.initialize(config);
            abilities.add(ability);
        }
    }
    
    /**
     * 移除能力
     */
    public void removeAbility(IBodyPartAbility ability) {
        abilities.remove(ability);
    }
    
    /**
     * 获取能力列表
     */
    public List<IBodyPartAbility> getAbilities() {
        return abilities;
    }
}