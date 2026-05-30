package com.catoxide.catoxidesbattlerebuild.mob;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

public class ModularZombie extends Zombie implements GeoEntity {
    private int hitTime = 0;
    private LivingEntity lastTarget = null;
    private double lastDistanceToTarget = 0;
    private long lastStateChangeTime = 0;

    private final ZombieAnimationController animationController;

    private static final EntityDataAccessor<Boolean> DATA_WINDUP =
            SynchedEntityData.defineId(ModularZombie.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_ALERTING =
            SynchedEntityData.defineId(ModularZombie.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_ALERT_COMPLETED =
            SynchedEntityData.defineId(ModularZombie.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_HAS_TARGET =
            SynchedEntityData.defineId(ModularZombie.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_IS_HIT =
            SynchedEntityData.defineId(ModularZombie.class, EntityDataSerializers.BOOLEAN);

    private AIManager aiManager;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public ModularZombie(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        this.aiManager = new AIManager(this);
        this.animationController = new ZombieAnimationController(this);
    }

    public AIManager getAIManager() {
        if (this.aiManager == null) {
            this.aiManager = new AIManager(this);
        }
        return this.aiManager;
    }

    public ZombieAnimationController getAnimationController() {
        return animationController;
    }

    @Override
    protected void registerGoals() {
        this.targetSelector.removeAllGoals(goal -> true);
        this.goalSelector.removeAllGoals(goal -> true);

        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SmartAttackGoal(this));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new SmartHurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new SmartNearestAttackableTargetGoal(this, Player.class, true));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        animationController.registerControllers(controllers);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_WINDUP, false);
        builder.define(DATA_ALERTING, false);
        builder.define(DATA_ALERT_COMPLETED, false);
        builder.define(DATA_HAS_TARGET, false);
        builder.define(DATA_IS_HIT, false);
    }

    public boolean hasTarget() {
        return this.entityData.get(DATA_HAS_TARGET);
    }

    public boolean isAlerting() {
        return this.entityData.get(DATA_ALERTING);
    }

    public boolean isAlertCompleted() {
        return this.entityData.get(DATA_ALERT_COMPLETED);
    }

    public boolean isHit() {
        return this.entityData.get(DATA_IS_HIT);
    }

    public boolean isWindingUp() {
        return this.entityData.get(DATA_WINDUP);
    }

    public boolean isInPostAttackCooldown() {
        return aiManager != null && aiManager.isInPostAttackCooldown();
    }

    public int getPostAttackCooldown() {
        return aiManager != null ? aiManager.getPostAttackCooldown() : 0;
    }

    public void checkAndResumeNavigation() {
        if (!this.level().isClientSide && this.getTarget() != null) {
            LivingEntity target = this.getTarget();
            double distance = this.distanceToSqr(target);
            double attackRange = getAIManager().getCurrentAttackRange();

            if (distance > (attackRange * attackRange) && this.getNavigation().isDone()) {
                this.getNavigation().moveTo(target, 1.0D);
            }
        }
    }

    public boolean isAttacking() {
        return isWindingUp() || isInPostAttackCooldown() || swinging;
    }

    public boolean isInCombatState() {
        return hasTarget() && (isWindingUp() || isInPostAttackCooldown() || isAlerting());
    }

    public double getLastDistanceToTarget() {
        return lastDistanceToTarget;
    }

    public void setLastDistanceToTarget(double distance) {
        this.lastDistanceToTarget = distance;
    }

    public void setWindingUp(boolean windingUp) {
        this.entityData.set(DATA_WINDUP, windingUp);
    }

    private void updateTargetState() {
        if (isHit()) {
            return;
        }

        LivingEntity currentTarget = this.getTarget();
        boolean newHasTarget = currentTarget != null && currentTarget.isAlive();

        if (hasTarget() != newHasTarget) {
            this.entityData.set(DATA_HAS_TARGET, newHasTarget);

            if (newHasTarget) {
                this.entityData.set(DATA_ALERTING, true);
                this.entityData.set(DATA_ALERT_COMPLETED, false);
                lastStateChangeTime = this.level().getGameTime();
            } else {
                this.entityData.set(DATA_ALERTING, false);
                this.entityData.set(DATA_ALERT_COMPLETED, false);
            }
        }
    }

    private boolean hasAggressiveTarget() {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }

        double followRange = this.getAttributeValue(Attributes.FOLLOW_RANGE);
        double distance = this.distanceTo(target);

        if (distance > followRange + 5.0) {
            return false;
        }

        if (this.getSensing().hasLineOfSight(target)) {
            return true;
        }

        if (target instanceof Player player) {
            return getAIManager().canHearTarget(player);
        }

        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isDeadOrDying()) {
            return false;
        }

        boolean hurt = super.hurt(source, amount);

        if (hurt) {
            if (aiManager != null) {
                aiManager.onHurt();
            }

            if (this.getHealth() <= 0.0F) {
                resetAllStates();
                return true;
            }

            this.entityData.set(DATA_IS_HIT, true);
            hitTime = 20;

            String hitAnim = determineHitAnimation(source);
            triggerAnim("MainController", hitAnim);

            this.getNavigation().stop();
        }

        return hurt;
    }

    private String determineHitAnimation(DamageSource source) {
        // TODO: 攻击者方向判定逻辑未生效，需要排查 source.getEntity() 返回值或角度计算问题
        // 后续通过 Mixin 修改 hurt() 方法时一起解决
        Entity attacker = source.getEntity();
        if (attacker == null) {
            LogManager.aiDebug(String.valueOf(getId()), "determineHitAnimation: attacker is null, using random");
            return this.getRandom().nextBoolean() ? "hit_front" : "hit_back";
        }

        double dx = attacker.getX() - this.getX();
        double dz = attacker.getZ() - this.getZ();
        float attackerAngle = (float) Math.toDegrees(Math.atan2(dz, dx));
        float zombieYaw = this.getYRot();

        float angleDiff = normalizeAngle(attackerAngle - zombieYaw);

        boolean isFront = angleDiff >= -90 && angleDiff <= 90;
        String result = isFront ? "hit_back" : "hit_front";
        LogManager.aiDebug(String.valueOf(getId()),
            String.format("determineHitAnimation: attacker=%s, dx=%.2f, dz=%.2f, attackerAngle=%.1f, zombieYaw=%.1f, angleDiff=%.1f, isFront=%s, result=%s",
                attacker.getName().getString(), dx, dz, attackerAngle, zombieYaw, angleDiff, isFront, result));
        return result;
    }

    private float normalizeAngle(float angle) {
        while (angle > 180) angle -= 360;
        while (angle <= -180) angle += 360;
        return angle;
    }

    private void resetAllStates() {
        this.entityData.set(DATA_IS_HIT, false);
        this.entityData.set(DATA_ALERTING, false);
        this.entityData.set(DATA_ALERT_COMPLETED, false);
        this.entityData.set(DATA_HAS_TARGET, false);
        this.entityData.set(DATA_WINDUP, false);

        animationController.reset();

        this.goalSelector.removeAllGoals(goal -> true);
        this.targetSelector.removeAllGoals(goal -> true);
    }

    @Override
    public void tick() {
        super.tick();

        if (this.isDeadOrDying()) return;

        if (isHit()) {
            hitTime--;
            if (hitTime <= 0) {
                this.entityData.set(DATA_IS_HIT, false);
            }
        }

        if (!this.level().isClientSide) {
            boolean isWindingUp = aiManager.isWindingUp();
            if (this.isWindingUp() != isWindingUp) {
                setWindingUp(isWindingUp);
            }
        }

        if (!isHit()) {
            updateTargetState();
        }

        if (isAlerting() && !isAlertCompleted()) {
            if (this.level().getGameTime() - lastStateChangeTime > 10) {
                this.entityData.set(DATA_ALERT_COMPLETED, true);
                this.entityData.set(DATA_ALERTING, false);
            }
        }

        animationController.tick();

        if (!this.level().isClientSide) {
            aiManager.tick();
        }
    }

    public String getCurrentStateInfo() {
        return animationController.getAnimationStateInfo();
    }
}