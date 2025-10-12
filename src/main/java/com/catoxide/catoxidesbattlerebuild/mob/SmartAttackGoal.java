package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class SmartAttackGoal extends Goal {
    private final ModularZombie zombie;
    private AIManager aiManager;

    public SmartAttackGoal(ModularZombie zombie) {
        this.zombie = zombie;
        this.aiManager = zombie.getAIManager();
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // 添加空值检查
        if (aiManager == null) {
            return false;
        }

        LivingEntity target = zombie.getTarget();
        return target != null && target.isAlive() &&
                zombie.getSensing().hasLineOfSight(target) &&
                aiManager.isInAttackRange(target);
    }

    @Override
    public void start() {
        // 什么都不做，等待 tick 处理
    }

    @Override
    public void tick() {
        if (aiManager == null) {
            System.out.println("SmartAttackGoal: aiManager 为 null");
            return;
        }

        LivingEntity target = zombie.getTarget();
        if (target == null) {
            System.out.println("SmartAttackGoal: target 为 null");
            return;
        }

        // 如果正在前摇中，只处理朝向
        if (aiManager.isWindingUp()) {
            zombie.getLookControl().setLookAt(target, 30.0F, 30.0F);
            System.out.println("SmartAttackGoal: 攻击前摇中，停止移动");
            return;
        }

        double distance = zombie.distanceToSqr(target);
        double attackRangeSqr = aiManager.getAttackRangeSqr();

        System.out.printf("SmartAttackGoal: 距离: %.2f, 攻击范围: %.2f%n",
                Math.sqrt(distance), Math.sqrt(attackRangeSqr));

        if (distance <= attackRangeSqr) {
            // 进入攻击范围，停止移动并开始前摇
            zombie.getNavigation().stop();
            aiManager.startWindUp();
            System.out.println("SmartAttackGoal: 进入攻击范围，开始前摇");
        } else {
            // 追逐目标
            boolean moveResult = zombie.getNavigation().moveTo(target, 1.0D);
            System.out.printf("SmartAttackGoal: 尝试移动到目标，结果: %s%n", moveResult);

            // 如果导航失败，尝试直接设置位置
            if (!moveResult) {
                System.out.println("SmartAttackGoal: 导航失败，尝试备用方案");
                // 备用移动方案
                zombie.getLookControl().setLookAt(target, 30.0F, 30.0F);
                zombie.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1.0D);
            }
        }

        // 始终看向目标
        zombie.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }


    @Override
    public void stop() {
        if (aiManager != null) {
            aiManager.stopWindUp();
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}