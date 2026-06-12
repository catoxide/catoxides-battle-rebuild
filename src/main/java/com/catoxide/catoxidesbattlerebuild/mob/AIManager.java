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

    private boolean isWindingUp = false;
    private int windUpTicks = 0;
    private final int maxWindUp = 10;
    private double originalSpeed;
    private float currentAttackRange = 2.0f;
    private int lostTargetTime = 0;
    private final int maxLostTargetTime = 100;
    private int postAttackCooldown = 0;
    private final int minPostAttackTime = 20;

    // 冲刺相关
    private boolean isSprinting = false;
    private int sprintCooldown = 0;
    private final int maxSprintCooldown = 120;  // 6秒冷却
    private int sprintDuration = 0;
    private final int maxSprintDuration = 30;   // 1.5秒冲刺
    private final double sprintSpeedMultiplier = 1.5;
    private final double sprintRange = 5.0;     // 5格内触发冲刺

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
        updateTargetTracking();
        updateAttackRange();
        combatSystem.tick();

        if (isWindingUp) {
            windUpTicks++;

            if (windUpTicks >= maxWindUp) {
                stopWindUp();
            }
        }

        if (postAttackCooldown > 0) {
            postAttackCooldown--;
        }

        // 冲刺逻辑
        updateSprint();
    }

    private void updateAttackRange() {
        float baseRange = 2.0f;
        ItemStack mainHandItem = zombie.getMainHandItem();

        if (!mainHandItem.isEmpty()) {
            baseRange += getWeaponRangeBonus(mainHandItem);
        }

        currentAttackRange = Math.min(baseRange, 3.5f);
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

    public void startWindUp() {
        isWindingUp = true;
        windUpTicks = 0;
        zombie.getNavigation().stop();
        zombie.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(originalSpeed * 0.1);
        spawnWindUpParticles();
    }

    public void stopWindUp() {
        isWindingUp = false;
        windUpTicks = 0;
        zombie.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(originalSpeed);

        LivingEntity target = zombie.getTarget();
        if (target != null) {
            double distance = zombie.distanceToSqr(target);
            double attackRange = getCurrentAttackRange();

            if (distance > (attackRange * attackRange)) {
                zombie.getNavigation().moveTo(target, 1.0D);
            }
        }
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

        if (distance > 100.0D) return false;

        if (target.isSprinting() && distance < 64.0D) return true;
        if (target.attackAnim > 0 && distance < 36.0D) return true;
        if (target.isUsingItem() && distance < 25.0D) return true;

        return false;
    }

    public void onHurt() {
        combatSystem.onHurt();
        stopSprint();
    }

    // ==================== 冲刺相关方法 ====================

    private void updateSprint() {
        if (sprintCooldown > 0) {
            sprintCooldown--;
        }

        if (isSprinting) {
            sprintDuration++;

            if (sprintDuration >= maxSprintDuration) {
                stopSprint();
            }
        } else {
            tryStartSprint();
        }
    }

    private void tryStartSprint() {
        if (sprintCooldown > 0) return;
        if (isWindingUp) return;
        if (postAttackCooldown > 0) return;

        LivingEntity target = zombie.getTarget();
        if (target == null || !target.isAlive()) return;

        double distance = zombie.distanceTo(target);
        // 在5格以内且超出攻击范围时冲刺
        if (distance > currentAttackRange && distance <= sprintRange) {
            startSprint();
        }
    }

    private void startSprint() {
        isSprinting = true;
        sprintDuration = 0;
        sprintCooldown = maxSprintCooldown;

        zombie.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(originalSpeed * sprintSpeedMultiplier);
        
        // 冲刺时朝向目标并移动
        LivingEntity target = zombie.getTarget();
        if (target != null) {
            zombie.getLookControl().setLookAt(target, 30.0F, 30.0F);
            zombie.getNavigation().moveTo(target, 1.0D);
        }
    }

    private void stopSprint() {
        if (isSprinting) {
            isSprinting = false;
            sprintDuration = 0;
            zombie.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(originalSpeed);
        }
    }

    public boolean isSprinting() {
        return isSprinting;
    }

    public boolean canSprint() {
        return sprintCooldown == 0 && !isWindingUp && postAttackCooldown == 0;
    }
}