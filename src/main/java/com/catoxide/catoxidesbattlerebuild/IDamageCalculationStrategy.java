package com.catoxide.catoxidesbattlerebuild;

import com.catoxide.catoxidesbattlerebuild.damage.CompositeDamage;
import com.catoxide.catoxidesbattlerebuild.damage.DamageResult;
import net.minecraft.world.entity.LivingEntity;

// 伤害计算策略接口
public interface IDamageCalculationStrategy {
    /**
     * 计算最终伤害结果
     */
    DamageResult calculateDamage(CompositeDamage compositeDamage);

    /**
     * 应用伤害到目标
     */
    void applyDamage(LivingEntity target, DamageResult result);
}
