// SmartAttackGoal.java - 简化版本
package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class SmartAttackGoal extends Goal {
    private final ModularZombie zombie;
    private AIManager aiManager;
    private int attackDelay = 0;

    public SmartAttackGoal(ModularZombie zombie) {
        this.zombie = zombie;
        this.aiManager = zombie.getAIManager();
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (aiManager == null) {
            aiManager = zombie.getAIManager();
            return false;
        }

        LivingEntity target = zombie.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        // 重置攻击延迟
        attackDelay = 0;
    }

    @Override
    public void tick() {
        LivingEntity target = zombie.getTarget();
        if (target == null) return;

        // 更新攻击延迟
        if (attackDelay > 0) {
            attackDelay--;
        }

        // 看向目标
        zombie.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distance = zombie.distanceToSqr(target);
        double attackRange = aiManager.getCurrentAttackRange();

        // 如果在攻击范围内，尝试攻击
        if (distance <= (attackRange * attackRange)) {
            // 停止移动
            zombie.getNavigation().stop();

            // 检查攻击延迟
            if (attackDelay <= 0 && !aiManager.isWindingUp()) {
                // 开始攻击
                aiManager.startWindUp();
                attackDelay = 20;
            }
        } else {
            // 追逐目标 - 确保导航是活动的
            if (!zombie.getNavigation().isDone()) {
                zombie.getNavigation().moveTo(target, 1.0D);
            } else {
                // 如果导航停止了，重新启动
                zombie.getNavigation().moveTo(target, 1.0D);
            }
        }
        // 始终看向目标
        zombie.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }

    @Override
    public void stop() {
        // 停止时重置状态
        if (aiManager != null) {
            aiManager.stopWindUp();
        }
        zombie.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}