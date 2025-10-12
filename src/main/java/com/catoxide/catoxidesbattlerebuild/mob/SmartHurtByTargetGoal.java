package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;

// 智能受伤复仇目标选择
public class SmartHurtByTargetGoal extends HurtByTargetGoal {
    private final ModularZombie zombie;
    private AIManager aiManager;
    private int lastSeenTimer = 0;
    private final int maxLostTime = 60; // 3秒后丢失复仇目标

    public SmartHurtByTargetGoal(ModularZombie zombie) {
        super(zombie);
        this.zombie = zombie;
        this.aiManager = zombie.getAIManager();
        this.setAlertOthers(ModularZombie.class);
    }

    @Override
    public boolean canUse() {
        // 添加空值检查
        if (aiManager == null) {
            return false;
        }

        // 调用父类逻辑检查是否被伤害
        if (!super.canUse()) {
            return false;
        }

        // 检查攻击者是否还活着且在范围内
        LivingEntity attacker = this.zombie.getLastHurtByMob();
        if (attacker == null || !attacker.isAlive()) {
            return false;
        }

        // 检查距离
        double distance = this.zombie.distanceToSqr(attacker);
        double followRange = this.zombie.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (distance > followRange * followRange) {
            return false;
        }

        return true;
    }

    @Override
    public boolean canContinueToUse() {
        // 添加空值检查
        if (aiManager == null) {
            return false;
        }

        LivingEntity target = this.zombie.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }

        // 如果看不到复仇目标，开始计时
        if (this.zombie.getSensing().hasLineOfSight(target)) {
            this.lastSeenTimer = 0;
        } else {
            this.lastSeenTimer++;
        }

        // 3秒没看到目标就放弃复仇
        if (this.lastSeenTimer > maxLostTime) {
            return false;
        }

        // 目标太远也放弃
        double distance = this.zombie.distanceToSqr(target);
        double followRange = this.zombie.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (distance > (followRange + 5.0) * (followRange + 5.0)) {
            return false;
        }

        // 使用 AI 管理器的目标丢失检测
        if (this.aiManager.shouldLoseTarget()) {
            return false;
        }

        return super.canContinueToUse();
    }

    @Override
    public void start() {
        super.start();
        this.lastSeenTimer = 0;
        System.out.println("开始复仇追踪");
    }

    @Override
    public void stop() {
        super.stop();
        System.out.println("停止复仇追踪");
    }
}