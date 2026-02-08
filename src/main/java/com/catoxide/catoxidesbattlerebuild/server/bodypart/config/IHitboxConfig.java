package com.catoxide.catoxidesbattlerebuild.server.bodypart.config;

import java.util.Map;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * Hitbox配置接口
 * 定义Hitbox的配置参数
 */
public interface IHitboxConfig {
    
    /**
     * 获取配置名称
     */
    String getConfigName();
    
    /**
     * 获取Hitbox名称
     */
    String getHitboxName();
    
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
     * 获取默认骨骼传导系数映射
     * @return 骨骼名称 -> 传导系数（0.0-1.0）
     */
    Map<String, Float> getDefaultBoneTransmissions();
    
    /**
     * 获取默认传导系数（用于未配置的骨骼）
     */
    float getDefaultTransmissionCoefficient();
    
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
