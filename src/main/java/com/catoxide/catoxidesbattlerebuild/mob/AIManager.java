package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

// 创建独立的 AI 管理器，可以附加到任何实体
public class AIManager {
    private final ModularZombie zombie;

    // AI 状态变量
    private boolean isChasing = false;
    private int lostTargetTime = 0;
    private final int maxLostTargetTime = 100;
    private boolean isWindingUp = false;
    private int windUpTicks = 0;
    private final int maxWindUp = 15;
    private double originalSpeed;
    private float currentAttackRange = 3.5f;
    private int windUpTimeout = 0;
    private final int maxWindUpTimeout = 40;

    public AIManager(ModularZombie zombie) {
        this.zombie = zombie;
        this.originalSpeed = zombie.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    public void tick() {
        updateAttackRange();
        updateTargetTracking();
    }

    private void updateAttackRange() {
        float baseRange = 3.5f;
        ItemStack mainHandItem = zombie.getMainHandItem();

        if (!mainHandItem.isEmpty()) {
            baseRange += getWeaponRangeBonus(mainHandItem);
        }

        currentAttackRange = baseRange;
    }

    private float getWeaponRangeBonus(ItemStack weapon) {
        if (weapon.getItem() instanceof SwordItem) {
            return getSwordRangeBonus(weapon);
        } else if (weapon.getItem() instanceof AxeItem) {
            return getAxeRangeBonus(weapon);
        } else if (weapon.getItem() instanceof TridentItem) {
            return 1.5f;
        }
        return 0.0f;
    }

    private float getSwordRangeBonus(ItemStack sword) {
        if (sword.is(Items.NETHERITE_SWORD)) return 1.2f;
        if (sword.is(Items.DIAMOND_SWORD)) return 1.0f;
        if (sword.is(Items.IRON_SWORD)) return 0.8f;
        if (sword.is(Items.GOLDEN_SWORD)) return 0.6f;
        if (sword.is(Items.STONE_SWORD)) return 0.7f;
        if (sword.is(Items.WOODEN_SWORD)) return 0.5f;
        return 0.5f;
    }

    private float getAxeRangeBonus(ItemStack axe) {
        if (axe.is(Items.NETHERITE_AXE)) return 0.8f;
        if (axe.is(Items.DIAMOND_AXE)) return 0.7f;
        if (axe.is(Items.IRON_AXE)) return 0.6f;
        if (axe.is(Items.GOLDEN_AXE)) return 0.4f;
        if (axe.is(Items.STONE_AXE)) return 0.5f;
        if (axe.is(Items.WOODEN_AXE)) return 0.3f;
        return 0.3f;
    }

    private void updateTargetTracking() {
        LivingEntity target = zombie.getTarget();
        if (target != null && zombie.getSensing().hasLineOfSight(target)) {
            lostTargetTime = 0;
        } else {
            lostTargetTime++;
        }
    }

    public boolean shouldLoseTarget() {
        return lostTargetTime > maxLostTargetTime;
    }

    public boolean isInAttackRange(LivingEntity target) {
        return zombie.distanceToSqr(target) <= getAttackRangeSqr();
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
    }

    private void executeAttack() {
        LivingEntity target = zombie.getTarget();
        if (target != null && isInAttackRange(target)) {
            System.out.printf("执行攻击 | 目标: %s | 距离: %.2f%n",
                    target.getName().getString(), zombie.distanceTo(target));
            zombie.doHurtTarget(target);

            // 触发攻击动画
            zombie.swing(zombie.getUsedItemHand());
        } else {
            System.out.printf("执行攻击失败 | 目标: %s | 在范围内: %s%n",
                    target != null ? target.getName().getString() : "null",
                    target != null ? isInAttackRange(target) : false);
        }

        // 恢复移动速度
        zombie.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(originalSpeed);
    }

    public boolean isWindingUp() {
        return isWindingUp;
    }

    // 修改 updateWindUp 方法
    public void updateWindUp() {
        if (isWindingUp) {
            windUpTicks++;
            windUpTimeout++;

            System.out.printf("攻击前摇更新 | tick: %d/%d | 超时: %d/%d | 目标: %s%n",
                    windUpTicks, maxWindUp, windUpTimeout, maxWindUpTimeout,
                    zombie.getTarget() != null ? zombie.getTarget().getName().getString() : "null");

            // 超时保护
            if (windUpTimeout >= maxWindUpTimeout) {
                System.out.println("攻击前摇超时，强制结束");
                stopWindUp();
                return;
            }

            if (windUpTicks >= maxWindUp) {
                System.out.println("攻击前摇完成，执行攻击");
                executeAttack();
                isWindingUp = false;
                windUpTicks = 0;
                windUpTimeout = 0;
            }
        }
    }

    // 修改 stopWindUp 方法
    public void stopWindUp() {
        isWindingUp = false;
        windUpTicks = 0;
        windUpTimeout = 0;
        zombie.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(originalSpeed);
        System.out.println("停止攻击前摇");
    }

    private void spawnWindUpParticles() {
        Level level = zombie.level();
        if (level.isClientSide) {
            ItemStack weapon = zombie.getMainHandItem();
            ParticleOptions particle = weapon.isEmpty() ?
                    ParticleTypes.ANGRY_VILLAGER : ParticleTypes.SWEEP_ATTACK;

            for (int i = 0; i < 8; i++) {
                double offsetX = zombie.getRandom().nextGaussian() * 0.5;
                double offsetY = zombie.getRandom().nextDouble() * 1.5;
                double offsetZ = zombie.getRandom().nextGaussian() * 0.5;
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

        System.out.printf("听觉检测 | 目标: %s | 距离: %.1f | 奔跑: %s | 攻击: %s | 使用物品: %s%n",
                target.getName().getString(), Math.sqrt(distance),
                target.isSprinting(), target.attackAnim > 0, target.isUsingItem());

        // 听觉范围限制
        if (distance > 144.0D) { // 12格外听不到
            System.out.println("听觉检测: 距离太远");
            return false;
        }

        // 各种声音检测
        if (target.isSprinting() && distance < 100.0D) { // 奔跑 - 10格
            System.out.println("听觉检测: 检测到奔跑");
            return true;
        }

        if (target.attackAnim > 0 && distance < 64.0D) { // 攻击 - 8格
            System.out.println("听觉检测: 检测到攻击");
            return true;
        }

        if (target.isUsingItem() && distance < 49.0D) { // 使用物品 - 7格
            System.out.println("听觉检测: 检测到使用物品");
            return true;
        }

        System.out.println("听觉检测: 未检测到声音");
        return false;
    }
}