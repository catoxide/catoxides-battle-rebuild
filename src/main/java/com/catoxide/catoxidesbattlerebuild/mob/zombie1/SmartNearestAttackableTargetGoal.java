package com.catoxide.catoxidesbattlerebuild.mob.zombie1;

import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;

public class SmartNearestAttackableTargetGoal extends NearestAttackableTargetGoal<Player> {
    private final ModularZombie zombie;
    private AIManager aiManager;
    private int lastSeenTimer = 0;

    public SmartNearestAttackableTargetGoal(ModularZombie zombie, Class<Player> targetClass, boolean mustSee) {
        super(zombie, targetClass, mustSee);
        this.zombie = zombie;
        this.aiManager = zombie.getAIManager();
    }

    @Override
    public boolean canUse() {
        if (aiManager == null) {
            return false;
        }

        boolean parentResult = super.canUse();
        if (!parentResult) {
            return false;
        }

        Player target = (Player) this.target;
        if (target == null) {
            return false;
        }

        boolean canSee = zombie.getSensing().hasLineOfSight(target);
        return canSee;
    }

    @Override
    public boolean canContinueToUse() {
        if (aiManager == null) {
            return false;
        }

        if (this.target == null || !this.target.isAlive()) return false;

        if (zombie.getSensing().hasLineOfSight(this.target)) {
            this.lastSeenTimer = 0;
        } else {
            this.lastSeenTimer++;
        }

        if (this.lastSeenTimer > 100) {
            return false;
        }

        if (aiManager.shouldLoseTarget()) {
            return false;
        }

        return zombie.distanceToSqr(this.target) < 256.0D;
    }

    @Override
    public void start() {
        super.start();
        this.lastSeenTimer = 0;
    }
}