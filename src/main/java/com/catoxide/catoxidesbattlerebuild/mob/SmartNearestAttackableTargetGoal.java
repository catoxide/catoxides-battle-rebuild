package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.world.entity.ai.attributes.Attributes;
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
            System.out.println("canUse: aiManager 为 null");
            return false;
        }

        // 先调用父类检查
        boolean parentResult = super.canUse();
        System.out.printf("canUse: 父类返回 %s%n", parentResult);

        if (!parentResult) {
            // 输出为什么父类返回 false
            Player nearestPlayer = this.zombie.level().getNearestPlayer(this.zombie,
                    this.zombie.getAttributeValue(Attributes.FOLLOW_RANGE));
            System.out.printf("canUse: 最近玩家: %s, 距离: %.2f%n",
                    nearestPlayer != null ? nearestPlayer.getName().getString() : "null",
                    nearestPlayer != null ? this.zombie.distanceTo(nearestPlayer) : -1);
            return false;
        }

        Player target = (Player) this.target;
        if (target == null) {
            System.out.println("canUse: target 为 null");
            return false;
        }

        // 简化条件 - 只要有视线就可以攻击
        boolean canSee = zombie.getSensing().hasLineOfSight(target);

        System.out.printf("canUse 检查 | 目标: %s | 可见: %s | 结果: %s%n",
                target.getName().getString(), canSee, canSee);

        return canSee;
    }

    @Override
    public boolean canContinueToUse() {
        // 添加空值检查
        if (aiManager == null) {
            return false;
        }

        if (this.target == null || !this.target.isAlive()) return false;

        // 更容易丢失目标
        if (zombie.getSensing().hasLineOfSight(this.target)) {
            this.lastSeenTimer = 0;
        } else {
            this.lastSeenTimer++;
        }

        // 5秒没看到目标就放弃
        if (this.lastSeenTimer > 100) {
            return false;
        }

        // 使用 AI 管理器的目标丢失检测
        if (aiManager.shouldLoseTarget()) {
            return false;
        }

        return zombie.distanceToSqr(this.target) < 256.0D;
    }

    @Override
    public void start() {
        super.start();
        this.lastSeenTimer = 0;
        System.out.println("开始目标追踪");
    }

    @Override
    public void stop() {
        super.stop();
        System.out.println("停止目标追踪");
    }
}
