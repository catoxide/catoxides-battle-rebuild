package com.catoxide.catoxidesbattlerebuild.server.bodypart;

import net.minecraft.world.damagesource.DamageSource;
import java.util.List;

/**
 * 身体部位接口
 * 定义身体部位的核心功能和行为
 * 
 * 新架构：BodyPart包含多个BodyUnit
 * 每个BodyUnit与Bone是1对1关系
 * BodyPart通过BodyUnit间接管理骨骼
 */
public interface IBodyPart {
    
    /**
     * 获取部位名称
     */
    String getPartName();
    
    /**
     * 获取当前血量
     * 计算所有BodyUnit的总血量
     */
    float getCurrentHealth();
    
    /**
     * 获取最大血量
     * 计算所有BodyUnit的最大血量总和
     */
    float getMaxHealth();
    
    /**
     * 设置当前血量
     * 分配到各个BodyUnit
     */
    void setCurrentHealth(float health);
    
    /**
     * 设置最大血量
     * 分配到各个BodyUnit
     */
    void setMaxHealth(float maxHealth);
    
    /**
     * 接收伤害
     * @param damage 伤害值
     * @param source 伤害来源
     * @return 实际受到的伤害
     */
    float receiveDamage(float damage, DamageSource source);
    
    /**
     * 按骨骼名称接收伤害
     * 找到对应的BodyUnit并传导伤害
     * @param boneName 骨骼名称
     * @param damage 伤害值
     * @param source 伤害来源
     * @return 实际受到的伤害
     */
    float receiveDamageByBone(String boneName, float damage, DamageSource source);
    
    /**
     * 传导伤害到实体
     * @param damage 要传导的伤害值
     */
    void transmitToEntity(float damage);
    
    /**
     * 检查是否为暴击区域
     * 如果任何一个BodyUnit是暴击区域，则返回true
     */
    boolean isCritical();
    
    /**
     * 检查是否为致命区域
     * 如果任何一个BodyUnit是致命区域，则返回true
     */
    boolean isFatal();
    
    /**
     * 获取所有BodyUnit
     * @return BodyUnit列表
     */
    List<BodyUnit> getBodyUnits();
    
    /**
     * 根据骨骼名称获取对应的BodyUnit
     * @param boneName 骨骼名称
     * @return 对应的BodyUnit，如果不存在则返回null
     */
    BodyUnit getUnitByBoneName(String boneName);
    
    /**
     * 检查部位是否存活
     * 如果所有BodyUnit都死亡，则部位死亡
     */
    boolean isAlive();
    
    /**
     * 重置部位状态
     */
    void reset();
}