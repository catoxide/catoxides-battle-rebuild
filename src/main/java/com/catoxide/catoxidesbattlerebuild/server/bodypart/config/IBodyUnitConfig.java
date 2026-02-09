package com.catoxide.catoxidesbattlerebuild.server.bodypart.config;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * BodyUnit配置接口
 * 定义BodyUnit的配置参数
 * 
 * 新架构：BodyUnit与Bone是1对1关系
 * 每个BodyUnit关联一个特定的骨骼
 */
public interface IBodyUnitConfig {
    
    /**
     * 获取配置名称
     */
    String getConfigName();
    
    /**
     * 获取BodyUnit名称
     */
    String getBodyUnitName();
    
    /**
     * 获取关联的骨骼名称
     * BodyUnit与Bone是1对1关系
     * @return 骨骼名称
     */
    String getBoneName();
    
    /**
     * 获取最大血量
     */
    float getMaxHealth();
    
    /**
     * 获取护甲值
     */
    float getArmorValue();
    
    /**
     * 检查是否为暴击区域
     */
    boolean isCritical();
    
    /**
     * 获取碰撞标签
     */
    String getCollisionTag();
    
    /**
     * 获取伤害传导系数（传导到BodyPart的比例）
     * @return 传导系数（0.0-1.0）
     * todo:1以上的传导系数
     */
    float getTransmissionCoefficient();
    
    /**
     * 获取特殊效果列表
     */
    List<String> getSpecialEffects();
    
    /**
     * 获取击中音效
     */
    ResourceLocation getHitSound();
    
    /**
     * 获取能力类名列表
     */
    List<String> getAbilityClassNames();
    
    /**
     * 获取实体传导系数（传导到实体血量的比例）
     */
    float getEntityTransmissionCoefficient();
    
    /**
     * 检查是否致命
     */
    boolean isFatal();
    
    /**
     * 获取致命阈值（血量低于此值时触发致命效果）
     */
    float getFatalThreshold();
}