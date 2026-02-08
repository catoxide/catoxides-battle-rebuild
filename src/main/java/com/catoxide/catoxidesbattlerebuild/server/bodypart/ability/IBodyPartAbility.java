package com.catoxide.catoxidesbattlerebuild.server.bodypart.ability;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.IBodyPartConfig;
import net.minecraft.world.damagesource.DamageSource;

/**
 * 身体部位能力接口
 * 定义身体部位的特殊能力
 */
public interface IBodyPartAbility {
    
    /**
     * 获取能力名称
     */
    String getAbilityName();
    
    /**
     * 初始化能力
     * @param config 部位配置
     */
    void initialize(IBodyPartConfig config);
    
    /**
     * 伤害处理前钩子
     * @param incomingDamage 即将受到的伤害
     * @return 修改后的伤害值
     */
    default float onPreDamage(float incomingDamage) {
        return incomingDamage;
    }
    
    /**
     * 伤害处理后钩子
     * @param calculatedDamage 计算后的伤害
     * @return 最终伤害值
     */
    default float onPostDamage(float calculatedDamage) {
        return calculatedDamage;
    }
    
    /**
     * 击中时触发
     * @param damage 伤害值
     * @param source 伤害来源
     */
    default void onHit(float damage, DamageSource source) {
        // 默认不执行任何操作
    }
    
    /**
     * 检查致命条件
     * @param currentHealth 当前血量
     * @param damage 伤害值
     * @return 是否致命
     */
    default boolean checkFatalCondition(float currentHealth, float damage) {
        return false;
    }
    
    /**
     * 重置能力状态
     */
    default void reset() {
        // 默认不执行任何操作
    }
}
