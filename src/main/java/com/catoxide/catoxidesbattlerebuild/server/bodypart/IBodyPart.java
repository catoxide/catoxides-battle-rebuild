package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import net.minecraft.world.damagesource.DamageSource;
import java.util.Map;

/**
 * 身体部位接口
 * 定义身体部位的核心功能和行为
 */
public interface IBodyPart {
    
    /**
     * 获取部位名称
     */
    String getPartName();
    
    /**
     * 获取当前血量
     */
    float getCurrentHealth();
    
    /**
     * 获取最大血量
     */
    float getMaxHealth();
    
    /**
     * 接收伤害
     * @param damage 伤害值
     * @param source 伤害来源
     * @return 实际受到的伤害
     */
    float receiveDamage(float damage, DamageSource source);
    
    /**
     * 传导伤害到实体
     * @param damage 要传导的伤害值
     */
    void transmitToEntity(float damage);
    
    /**
     * 检查是否为暴击区域
     */
    boolean isCritical();
    
    /**
     * 检查是否为致命区域
     */
    boolean isFatal();
    
    /**
     * 添加骨骼到该部位
     * @param boneName 骨骼名称
     * @param transmissionCoefficient 伤害传导系数
     */
    void addBone(String boneName, float transmissionCoefficient);
    
    /**
     * 获取所有骨骼的传导系数
     */
    Map<String, Float> getBoneTransmissionCoefficients();
    
    /**
     * 检查部位是否存活
     */
    boolean isAlive();
    
    /**
     * 重置部位状态
     */
    void reset();
}
