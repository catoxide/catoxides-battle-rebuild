package com.catoxide.catoxidesbattlerebuild.mob.zombie1;

import net.minecraft.world.entity.LivingEntity;

public class SmartCombatSystem {
    private final ModularZombie zombie;
    private final AIManager aiManager;

    private boolean isAttacking = false;
    private int attackCooldown = 0;
    private final int attackInterval = 20;

    public SmartCombatSystem(ModularZombie zombie, AIManager aiManager) {
        this.zombie = zombie;
        this.aiManager = aiManager;
    }

    public void tick() {
        if (attackCooldown > 0) {
            attackCooldown--;
        }

        if (canAttack()) {
            startAttack();
        }

        if (isAttacking) {
            updateAttack();
        }
    }

    private boolean canAttack() {
        if (attackCooldown > 0) return false;

        LivingEntity target = zombie.getTarget();
        if (target == null || !target.isAlive()) return false;

        return aiManager.isInAttackRange(target) &&
                zombie.getSensing().hasLineOfSight(target);
    }

    private void startAttack() {
        isAttacking = true;
        attackCooldown = attackInterval;

        zombie.getNavigation().stop();
        aiManager.startWindUp();
    }

    private void updateAttack() {
        LivingEntity target = zombie.getTarget();
        if (target == null || !target.isAlive()) {
            cancelAttack();
            return;
        }

        zombie.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (!aiManager.isWindingUp()) {
            executeAttack();
            isAttacking = false;
        }
    }

    private void executeAttack() {
        LivingEntity target = zombie.getTarget();
        if (target != null && aiManager.isInAttackRange(target)) {
            zombie.doHurtTarget(target);
            zombie.swing(zombie.getUsedItemHand());
            aiManager.setPostAttackCooldown(aiManager.getMinPostAttackTime());
        }
    }

    private void cancelAttack() {
        isAttacking = false;
        aiManager.stopWindUp();

        LivingEntity target = zombie.getTarget();
        if (target != null) {
            double distance = zombie.distanceToSqr(target);
            double attackRange = aiManager.getCurrentAttackRange();

            if (distance > (attackRange * attackRange)) {
                zombie.getNavigation().moveTo(target, 1.0D);
            }
        }
    }

    public void onHurt() {
        cancelAttack();
    }
}