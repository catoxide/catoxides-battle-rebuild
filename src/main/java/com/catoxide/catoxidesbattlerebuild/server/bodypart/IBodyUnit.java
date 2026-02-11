package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import java.util.List;
import java.util.UUID;

/**
 * BodyUnit接口
 * BodyUnit作为血量单元，负责结算伤害比例和部位致命效果
 * 
 * 新架构：BodyUnit与Bone是1对1关系
 * 每个BodyUnit关联一个特定的骨骼
 */
public interface IBodyUnit {
    
    /**
     * 获取BodyUnit的唯一标识符
     */
    UUID getId();
    
    /**
     * 获取BodyUnit的名称
     */
    String getName();
    
    /**
     * 获取关联的实体ID
     */
    long getEntityId();
    
    // ==================== 骨骼管理 ====================
    
    /**
     * 获取关联的骨骼名称
     * BodyUnit与Bone是1对1关系
     * @return 骨骼名称
     */
    String getBoneName();
    
    /**
     * 获取伤害传导系数（传导到BodyPart的比例）
     * @return 传导系数（0.0-1.0）
     */
    float getTransmissionCoefficient();
    
    /**
     * 检查骨骼名称是否匹配
     * @param boneName 要检查的骨骼名称
     * @return 是否匹配
     */
    boolean matchesBone(String boneName);
    
    // ==================== 伤害处理 ====================
    
    /**
     * 接收伤害（从骨骼传导）
     * 会验证骨骼名称是否匹配，只有匹配的骨骼才能传导伤害
     * @param boneName 来源骨骼
     * @param rawDamage 原始伤害值
     * @param damageType 伤害类型
     * @return 实际受到的伤害
     */
    float receiveDamage(String boneName, float rawDamage, String damageType);
    
    /**
     * 计算传导到实体血量的伤害
     * @param damage BodyUnit受到的伤害
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
     * 检查是否存活
     * 调用BodyPart的isAlive方法
     */
    boolean isAlive();
    
    /**
     * 重置状态
     */
    void reset();
    
    // ==================== 能力系统 ====================
    
    /**
     * 获取能力列表
     */
    List<IBodyUnitAbility> getAbilities();
    
    /**
     * 添加能力
     */
    void addAbility(IBodyUnitAbility ability);
    
    /**
     * 移除能力
     */
    void removeAbility(IBodyUnitAbility ability);
}