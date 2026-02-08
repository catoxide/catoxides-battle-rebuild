package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Hitbox接口
 * Hitbox作为血量单元，负责结算伤害比例和部位致命效果
 * Hitbox包含多个Bones，每个Bone有伤害传导系数
 */
public interface IHitbox {
    
    /**
     * 获取Hitbox的唯一标识符
     */
    UUID getId();
    
    /**
     * 获取Hitbox的名称
     */
    String getName();
    
    /**
     * 获取关联的实体ID
     */
    long getEntityId();
    
    // ==================== 血量管理 ====================
    
    /**
     * 获取当前血量
     */
    float getCurrentHealth();
    
    /**
     * 获取最大血量
     */
    float getMaxHealth();
    
    /**
     * 设置当前血量
     */
    void setCurrentHealth(float health);
    
    /**
     * 设置最大血量
     */
    void setMaxHealth(float maxHealth);
    
    /**
     * 检查是否存活
     */
    boolean isAlive();
    
    // ==================== 骨骼管理 ====================
    
    /**
     * 添加骨骼到Hitbox
     * @param boneName 骨骼名称
     * @param transmissionCoefficient 伤害传导系数（0.0-1.0）
     */
    void addBone(String boneName, float transmissionCoefficient);
    
    /**
     * 移除骨骼
     */
    void removeBone(String boneName);
    
    /**
     * 获取所有骨骼及其传导系数
     */
    Map<String, Float> getBones();
    
    /**
     * 获取指定骨骼的传导系数
     */
    float getTransmissionCoefficient(String boneName);
    
    /**
     * 检查是否包含指定骨骼
     */
    boolean hasBone(String boneName);
    
    // ==================== 伤害处理 ====================
    
    /**
     * 接收伤害（从骨骼传导）
     * @param boneName 来源骨骼
     * @param rawDamage 原始伤害值
     * @param damageType 伤害类型
     * @return 实际受到的伤害
     */
    float receiveDamage(String boneName, float rawDamage, String damageType);
    
    /**
     * 计算传导到实体血量的伤害
     * @param damage Hitbox受到的伤害
     * @return 传导到实体的伤害
     */
    float calculateEntityTransmission(float damage);
    
    /**
     * 检查是否致命
     */
    boolean isFatal();
    
    /**
     * 触发致命效果
     */
    void triggerFatalEffect();
    
    // ==================== 防护系统 ====================
    
    /**
     * 获取护甲值
     */
    float getArmorValue();
    
    /**
     * 设置护甲值
     */
    void setArmorValue(float armorValue);
    
    /**
     * 计算护甲减免
     */
    float calculateArmorReduction(float damage);
    
    // ==================== 区域标记 ====================
    
    /**
     * 检查是否为暴击区域
     */
    boolean isCritical();
    
    /**
     * 设置是否为暴击区域
     */
    void setCritical(boolean critical);
    
    /**
     * 获取碰撞标签
     */
    String getCollisionTag();
    
    /**
     * 设置碰撞标签
     */
    void setCollisionTag(String tag);
    
    // ==================== 特殊效果 ====================
    
    /**
     * 获取特殊效果列表
     */
    List<String> getSpecialEffects();
    
    /**
     * 添加特殊效果
     */
    void addSpecialEffect(String effect);
    
    /**
     * 移除特殊效果
     */
    void removeSpecialEffect(String effect);
    
    // ==================== 击中事件 ====================
    
    /**
     * 记录击中事件
     */
    void recordHit(float damage);
    
    /**
     * 获取击中次数
     */
    int getHitCount();
    
    /**
     * 获取最近击中时间戳
     */
    long getLastHitTime();
    
    // ==================== 状态管理 ====================
    
    /**
     * 检查是否激活
     */
    boolean isActive();
    
    /**
     * 设置激活状态
     */
    void setActive(boolean active);
    
    /**
     * 重置状态
     */
    void reset();
    
    // ==================== 能力系统 ====================
    
    /**
     * 获取能力列表
     */
    List<IHitboxAbility> getAbilities();
    
    /**
     * 添加能力
     */
    void addAbility(IHitboxAbility ability);
    
    /**
     * 移除能力
     */
    void removeAbility(IHitboxAbility ability);
}
