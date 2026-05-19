package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;

public class SmartHurtByTargetGoal extends HurtByTargetGoal {
    private final ModularZombie zombie;
    private AIManager aiManager;
    private int lastSeenTimer = 0;
    private final int maxLostTime = 60;

    public SmartHurtByTargetGoal(ModularZombie zombie) {
        super(zombie);
        this.zombie = zombie;
        this.aiManager = zombie.getAIManager();
        this.setAlertOthers(ModularZombie.class);
    }

    @Override
    public boolean canUse() {
        if (aiManager == null) {
            return false;
        }

        if (!super.canUse()) {
            return false;
        }

        LivingEntity attacker = this.zombie.getLastHurtByMob();
        if (attacker == null || !attacker.isAlive()) {
            return false;
        }

        double distance = this.zombie.distanceToSqr(attacker);
        double followRange = this.zombie.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (distance > followRange * followRange) {
            return false;
        }

        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (aiManager == null) {
            return false;
        }

        LivingEntity target = this.zombie.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }

        if (this.zombie.getSensing().hasLineOfSight(target)) {
            this.lastSeenTimer = 0;
        } else {
            this.lastSeenTimer++;
        }

        if (this.lastSeenTimer > maxLostTime) {
            return false;
        }

        double distance = this.zombie.distanceToSqr(target);
        double followRange = this.zombie.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (distance > (followRange + 5.0) * (followRange + 5.0)) {
            return false;
        }

        if (this.aiManager.shouldLoseTarget()) {
            return false;
        }

        return super.canContinueToUse();
    }

    @Override
    public void start() {
        super.start();
        this.lastSeenTimer = 0;
    }
}