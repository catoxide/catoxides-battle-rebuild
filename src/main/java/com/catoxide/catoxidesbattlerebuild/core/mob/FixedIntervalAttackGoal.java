package com.catoxide.catoxidesbattlerebuild.core.mob;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * 固定攻击间隔的近战目标
 * <p>参考原版 {@code ZombieAttackGoal}：攻击冷却固定为指定 tick 数，
 * <b>不依赖 ATTACK_SPEED</b>（裸 MeleeAttackGoal 的冷却 = getCurrentSwingDuration = 6/ATTACK_SPEED，
 * ATTACK_SPEED=4 时仅 2 tick → 疯狂挥动 → swinging 持续 → 动画卡死/抬手蠕动/移动异常）。
 *
 * @param attackInterval 攻击间隔（tick），如 20 = 每 1 秒一次攻击
 */
public class FixedIntervalAttackGoal extends MeleeAttackGoal {

    private final int attackInterval;

    public FixedIntervalAttackGoal(PathfinderMob mob, double speedModifier, boolean followUnseen, int attackInterval) {
        super(mob, speedModifier, followUnseen);
        this.attackInterval = attackInterval;
    }

    @Override
    protected int getTicksUntilNextAttack() {
        return this.attackInterval;
    }
}
