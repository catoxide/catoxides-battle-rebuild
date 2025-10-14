// 修改 AIManager.java - 简化版本
package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

public class AIManager {
    private final ModularZombie zombie;
    private final SmartCombatSystem combatSystem;

    // AI 状态变量
    private boolean isWindingUp = false;
    private int windUpTicks = 0;
    private final int maxWindUp = 10; // 减少前摇时间
    private double originalSpeed;
    private float currentAttackRange = 2.0f; // 更保守的攻击范围
    private int lostTargetTime = 0;
    private final int maxLostTargetTime = 100;
    // 攻击后冷却相关字段
    private int postAttackCooldown = 0;
    private final int minPostAttackTime = 20; // 最少保持攻击状态 20 ticks

    public AIManager(ModularZombie zombie) {
        this.zombie = zombie;
        this.combatSystem = new SmartCombatSystem(zombie, this);
        this.originalSpeed = zombie.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }
    public boolean isInPostAttackCooldown() {
        return postAttackCooldown > 0;
    }

    public int getPostAttackCooldown() {
        return postAttackCooldown;
    }

    public void setPostAttackCooldown(int cooldown) {
        this.postAttackCooldown = cooldown;
    }


    public void tick() {
        updateAttackRange();
        combatSystem.tick();

        // 更新攻击前摇
        if (isWindingUp) {
            windUpTicks++;

            if (windUpTicks >= maxWindUp) {
                System.out.println("攻击前摇完成");
                stopWindUp();
            }
        }
        // 更新攻击后冷却
        if (postAttackCooldown > 0) {
            postAttackCooldown--;
        }
    }

    private void updateAttackRange() {
        float baseRange = 2.0f; // 基础攻击范围
        ItemStack mainHandItem = zombie.getMainHandItem();

        if (!mainHandItem.isEmpty()) {
            baseRange += getWeaponRangeBonus(mainHandItem);
        }

        currentAttackRange = Math.min(baseRange, 3.5f); // 限制最大范围
    }

    private float getWeaponRangeBonus(ItemStack weapon) {
        if (weapon.getItem() instanceof SwordItem) return 0.5f;
        if (weapon.getItem() instanceof AxeItem) return 0.3f;
        if (weapon.getItem() instanceof TridentItem) return 1.0f;
        return 0.0f;
    }

    public boolean isInAttackRange(LivingEntity target) {
        double distance = zombie.distanceTo(target);
        return distance <= currentAttackRange;
    }

    public double getAttackRangeSqr() {
        return currentAttackRange * currentAttackRange;
    }

    public float getCurrentAttackRange() {
        return currentAttackRange;
    }

    // 攻击前摇相关方法
    public void startWindUp() {
        isWindingUp = true;
        windUpTicks = 0;
        zombie.getNavigation().stop();
        zombie.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(originalSpeed * 0.1);
        spawnWindUpParticles();
        System.out.println("开始攻击前摇");
    }

    public void stopWindUp() {
        isWindingUp = false;
        windUpTicks = 0;
        zombie.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(originalSpeed);

        // 攻击前摇结束后，检查是否需要重新移动
        LivingEntity target = zombie.getTarget();
        if (target != null) {
            double distance = zombie.distanceToSqr(target);
            double attackRange = getCurrentAttackRange();

            // 如果目标不在攻击范围内，应该继续移动
            if (distance > (attackRange * attackRange)) {
                // 重新启动导航
                zombie.getNavigation().moveTo(target, 1.0D);
            }
        }

        System.out.println("停止攻击前摇");
    }
    private void updateTargetTracking() {
        LivingEntity target = zombie.getTarget();
        if (target != null && zombie.getSensing().hasLineOfSight(target)) {
            lostTargetTime = 0; // 重置丢失时间
        } else {
            lostTargetTime++; // 增加丢失时间
        }
    }
    public boolean shouldLoseTarget() {
        return lostTargetTime > maxLostTargetTime;
    }

    public boolean isWindingUp() {
        return isWindingUp;
    }
    public int getMinPostAttackTime() {
        return minPostAttackTime;
    }


    private void spawnWindUpParticles() {
        Level level = zombie.level();
        if (level.isClientSide) {
            ParticleOptions particle = ParticleTypes.SWEEP_ATTACK;

            for (int i = 0; i < 5; i++) {
                double offsetX = zombie.getRandom().nextGaussian() * 0.3;
                double offsetY = zombie.getRandom().nextDouble() * 1.0;
                double offsetZ = zombie.getRandom().nextGaussian() * 0.3;
                level.addParticle(particle,
                        zombie.getX() + offsetX,
                        zombie.getY() + offsetY,
                        zombie.getZ() + offsetZ,
                        0, 0, 0);
            }
        }
    }

    public boolean canHearTarget(Player target) {
        double distance = zombie.distanceToSqr(target);

        // 简化听觉检测
        if (distance > 100.0D) return false; // 10格外听不到

        // 各种声音检测
        if (target.isSprinting() && distance < 64.0D) return true;
        if (target.attackAnim > 0 && distance < 36.0D) return true;
        if (target.isUsingItem() && distance < 25.0D) return true;

        return false;
    }

    public void onHurt() {
        combatSystem.onHurt();
    }
}