package com.catoxide.catoxidesbattlerebuild.server.bodypart.config;

import java.util.List;

/**
 * 身体部位配置接口
 * 定义身体部位的配置参数
 */
public interface IBodyPartConfig {
    
    /**
     * 获取配置名称
     */
    String getConfigName();
    
    /**
     * 获取基础血量
     */
    float getBaseHealth();
    
    /**
     * 获取伤害倍率
     */
    float getDamageMultiplier();
    
    /**
     * 获取护甲值
     */
    float getArmorValue();
    
    /**
     * 获取护甲韧性
     */
    float getArmorToughness();
    
    /**
     * 检查是否为暴击区域
     */
    boolean isCriticalZone();
    
    /**
     * 检查是否为致命区域
     */
    boolean isFatalZone();
    
    /**
     * 获取默认能力列表
     */
    List<String> getDefaultAbilities();
    
    /**
     * 获取伤害传导系数（默认值）
     */
    float getDefaultTransmissionCoefficient();
}
