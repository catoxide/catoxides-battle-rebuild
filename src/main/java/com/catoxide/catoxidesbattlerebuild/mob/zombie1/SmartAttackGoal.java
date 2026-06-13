package com.catoxide.catoxidesbattlerebuild.mob.zombie1;

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
        }
        
        if (aiManager == null) return false;
        
        LivingEntity target = zombie.getTarget();
        return target != null && target.isAlive() && !aiManager.shouldLoseTarget();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        attackDelay = 0;
    }

    @Override
    public void tick() {
        LivingEntity target = zombie.getTarget();
        if (target == null || aiManager == null) return;

        zombie.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distance = zombie.distanceToSqr(target);
        double attackRange = aiManager.getCurrentAttackRange();

        if (distance <= (attackRange * attackRange)) {
            zombie.getNavigation().stop();
        } else {
            if (!zombie.getNavigation().isInProgress()) {
                zombie.getNavigation().moveTo(target, 1.0D);
            }
        }
    }

    @Override
    public void stop() {
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