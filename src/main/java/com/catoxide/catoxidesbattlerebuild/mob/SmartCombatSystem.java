package com.catoxide.catoxidesbattlerebuild.mob;



import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class SmartCombatSystem {
    private final ModularZombie zombie;
    private final AIManager aiManager;

    // 攻击状态
    private boolean isAttacking = false;
    private int attackCooldown = 0;
    private final int attackInterval = 20; // 攻击间隔

    public SmartCombatSystem(ModularZombie zombie, AIManager aiManager) {
        this.zombie = zombie;
        this.aiManager = aiManager;
    }

    public void tick() {
        // 更新攻击冷却
        if (attackCooldown > 0) {
            attackCooldown--;
        }

        // 检查是否可以攻击
        if (canAttack()) {
            startAttack();
        }

        // 更新攻击状态
        if (isAttacking) {
            updateAttack();
        }
    }

    private boolean canAttack() {
        if (attackCooldown > 0) return false;

        LivingEntity target = zombie.getTarget();
        if (target == null || !target.isAlive()) return false;

        // 检查距离和视线
        return aiManager.isInAttackRange(target) &&
                zombie.getSensing().hasLineOfSight(target);
    }

    private void startAttack() {
        isAttacking = true;
        attackCooldown = attackInterval;

        // 停止移动
        zombie.getNavigation().stop();

        // 开始攻击前摇
        aiManager.startWindUp();

        System.out.println("开始攻击序列");
    }

    private void updateAttack() {
        // 检查目标是否仍然有效
        LivingEntity target = zombie.getTarget();
        if (target == null || !target.isAlive()) {
            cancelAttack();
            return;
        }

        // 看向目标
        zombie.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // 检查攻击前摇状态
        if (!aiManager.isWindingUp()) {
            // 攻击前摇完成，执行攻击
            executeAttack();
            isAttacking = false;
        }
    }
    //todo:攻击切换成范围判定？
    private void executeAttack() {
        LivingEntity target = zombie.getTarget();
        if (target != null && aiManager.isInAttackRange(target)) {
            System.out.println("执行攻击!");
            zombie.doHurtTarget(target);
            zombie.swing(zombie.getUsedItemHand());
            aiManager.setPostAttackCooldown(aiManager.getMinPostAttackTime());
        } else {
            System.out.println("攻击失败: 目标不在范围内");
        }
    }

    private void cancelAttack() {
        isAttacking = false;
        aiManager.stopWindUp();

        // 取消攻击时，确保导航可以恢复
        LivingEntity target = zombie.getTarget();
        if (target != null) {
            double distance = zombie.distanceToSqr(target);
            double attackRange = aiManager.getCurrentAttackRange();

            if (distance > (attackRange * attackRange)) {
                zombie.getNavigation().moveTo(target, 1.0D);
            }
        }

        System.out.println("取消攻击");
    }

    public void onHurt() {
        cancelAttack();
    }
}
