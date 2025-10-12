package com.catoxide.catoxidesbattlerebuild;

import com.catoxide.catoxidesbattlerebuild.damage.DamageType;

// 战斗架势接口
public interface ICombatStance {
    String getStanceId();
    String getDisplayName();

    /**
     * 获取攻击持续时间（ticks）
     */
    int getAttackDuration();

    /**
     * 获取攻击冷却时间（ticks）
     */
    int getCooldown();

    /**
     * 是否可以在该架势下移动
     */
    boolean canMove();

    /**
     * 获取该架势的伤害倍率
     */
    float getDamageMultiplier(DamageType damageType);

    /**
     * 获取攻击动画进度对应的碰撞箱缩放
     */
    default float getHitboxScale(float attackProgress) {
        return 1.0f; // 默认不缩放
    }
}
