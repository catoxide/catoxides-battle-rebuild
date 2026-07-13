package com.catoxide.catoxidesbattlerebuild.mob.zombie2;

import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.PathfinderMob;

/**
 * ModularZombie2：基于 {@link AnimatedMob} 的第一只 Spark-Core 动画生物。
 * <p>仅保留 zombie2 特有的状态机逻辑、AI 注册、受击处理。
 *
 * <p>作为 {@code ContentPack} 框架的内置 example，验证 AnimatedMob 抽象的正确性。
 * 未来第三方 DLC 可参考此实现构建自己的动画生物。
 */
public class ModularZombie2 extends AnimatedMob<ModularZombie2> {

    private int hitTime = 0;
    private long lastStateChangeTime = 0;

    // Synced entity data
    private static final EntityDataAccessor<Boolean> DATA_WINDUP =
            SynchedEntityData.defineId(ModularZombie2.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_ALERTING =
            SynchedEntityData.defineId(ModularZombie2.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_ALERT_COMPLETED =
            SynchedEntityData.defineId(ModularZombie2.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_HAS_TARGET =
            SynchedEntityData.defineId(ModularZombie2.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_IS_HIT =
            SynchedEntityData.defineId(ModularZombie2.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ANIM_STATE =
            SynchedEntityData.defineId(ModularZombie2.class, EntityDataSerializers.INT);

    // Animation states
    public static final int STATE_IDLE = 0;
    public static final int STATE_WALKING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_ATTACK = 3;
    public static final int STATE_ALERT = 4;
    public static final int STATE_HIT_FRONT = 5;
    public static final int STATE_HIT_BACK = 6;
    public static final int STATE_WINDING = 7;

    // 游荡动画滞回：游荡 AI 移动/停止周期约 1-2 秒，会和 walking 动画 length=2s 冲突，
    // 导致动画频繁从 0.0 帧重启，永远播不到迈步时刻（0.5s/1.5s），看起来两腿同步。
    // 进入 WALKING 后必须持续静止 N tick 才切回 IDLE，避免短暂停顿重置动画。
    private long lastMovingTick = 0;
    private static final long WALKING_HOLD_TICKS = 40; // 2 秒，覆盖一个完整步态周期

    public ModularZombie2(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        LogManager.zombie2Init(getId());
    }

    // ========== AnimatedMob 抽象方法实现 ==========

    @Override
    public ModelIndex getDefaultModelIndex() {
        return new ModelIndex(
            "entity",
            ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "modular_zombie_2")
        );
    }

    @Override
    public ResourceLocation getTextureLocation() {
        return ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "textures/entity/modular_zombie_2.png");
    }

    @Override
    public String getStateAnimationName(int state) {
        switch (state) {
            case STATE_IDLE: return "still";
            case STATE_WALKING: return "walking";
            case STATE_RUNNING: return "running";
            case STATE_ATTACK: return "attack";
            case STATE_ALERT: return "alert";
            case STATE_HIT_FRONT: return "hit_front";
            case STATE_HIT_BACK: return "hit_back";
            case STATE_WINDING: return "aggressive";
            default: return "still";
        }
    }

    @Override
    public int getAnimState() {
        return this.entityData.get(DATA_ANIM_STATE);
    }

    @Override
    public void setAnimState(int state) {
        this.entityData.set(DATA_ANIM_STATE, state);
    }

    @Override
    protected int determineAnimationState() {
        // 判断移动：AI 想要移动（moveControl 有目标）即为移动状态
        // 这样即使速度短暂为 0（被方块阻挡、路径计算）也判定为移动
        boolean isMoving = this.moveControl.hasWanted() || !this.getNavigation().isDone();
        LivingEntity target = this.getTarget();

        // 更新 lastMovingTick：用于 WALKING→IDLE 滞回
        if (isMoving) {
            lastMovingTick = this.level().getGameTime();
        }

        // Priority 1: Attack (swing animation)
        if (this.swinging) {
            return STATE_ATTACK;
        }

        // Priority 2: Winding up (pre-attack wind-up)
        if (isWindingUp()) {
            return STATE_WINDING;
        }

        // Priority 3: Alert transition animation (entering alert state)
        if (isAlerting() && !isAlertCompleted()) {
            return STATE_ALERT;
        }

        // Priority 4: Four basic states based on alert × moving
        boolean isAlert = hasTarget() || isAlertCompleted();
        if (isAlert) {
            return isMoving ? STATE_RUNNING : STATE_ALERT;
        } else {
            // 游荡滞回：如果当前是 WALKING，即使短暂静止也保持 WALKING，
            // 避免游荡 AI 频繁切换 IDLE↔WALKING 导致动画过渡混合期
            // 把 IDLE 的对称腿部数据混进 WALKING，造成两腿同步。
            if (!isMoving && getAnimState() == STATE_WALKING) {
                long idleSince = this.level().getGameTime() - lastMovingTick;
                if (idleSince < WALKING_HOLD_TICKS) {
                    return STATE_WALKING; // 保持 walking，让动画完整播放
                }
            }
            return isMoving ? STATE_WALKING : STATE_IDLE;
        }
    }

    @Override
    protected boolean shouldUpdateState(int currentState) {
        // 受击中不允许切换状态（仅限 hit 动画期间）
        if (isHit() && (currentState == STATE_HIT_FRONT || currentState == STATE_HIT_BACK)) {
            return false;
        }
        return true;
    }

    // ========== Goal Registration ==========
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.0D, false));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ========== Synced Data Definitions ==========
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_WINDUP, false);
        builder.define(DATA_ALERTING, false);
        builder.define(DATA_ALERT_COMPLETED, false);
        builder.define(DATA_HAS_TARGET, false);
        builder.define(DATA_IS_HIT, false);
        builder.define(DATA_ANIM_STATE, STATE_IDLE);
    }

    // ========== State Getters ==========
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

    public boolean isAttacking() {
        return isWindingUp() || swinging;
    }

    public boolean isInCombatState() {
        return hasTarget() && (isWindingUp() || isAlerting());
    }

    public void setWindingUp(boolean windingUp) {
        this.entityData.set(DATA_WINDUP, windingUp);
    }

    // ========== Target State Management ==========
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
                setAnimState(STATE_ALERT);
            } else {
                this.entityData.set(DATA_ALERTING, false);
                this.entityData.set(DATA_ALERT_COMPLETED, false);
                setAnimState(STATE_IDLE);
            }
        }
    }

    // ========== Hurt Handling ==========
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isDeadOrDying()) {
            return false;
        }

        boolean hurt = super.hurt(source, amount);

        if (hurt) {
            if (this.getHealth() <= 0.0F) {
                resetAllStates();
                return true;
            }

            this.entityData.set(DATA_IS_HIT, true);
            hitTime = 20;

            int hitState = determineHitAnimation(source);
            setAnimState(hitState);

            this.getNavigation().stop();
        }

        return hurt;
    }

    private int determineHitAnimation(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker == null) {
            return this.getRandom().nextBoolean() ? STATE_HIT_FRONT : STATE_HIT_BACK;
        }

        double dx = attacker.getX() - this.getX();
        double dz = attacker.getZ() - this.getZ();
        float attackerAngle = (float) Math.toDegrees(Math.atan2(dz, dx));
        float zombieYaw = this.getYRot();

        float angleDiff = normalizeAngle(attackerAngle - zombieYaw);

        boolean isFront = angleDiff >= -90 && angleDiff <= 90;
        return isFront ? STATE_HIT_BACK : STATE_HIT_FRONT;
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
        this.entityData.set(DATA_ANIM_STATE, STATE_IDLE);
    }

    // ========== Tick ==========
    @Override
    public void tick() {
        super.tick();

        if (this.isDeadOrDying()) return;

        // 禁用原版冲刺行为，后续再考虑引入自定义冲刺
        if (this.isSprinting()) {
            this.setSprinting(false);
        }

        if (isHit()) {
            hitTime--;
            if (hitTime <= 0) {
                this.entityData.set(DATA_IS_HIT, false);
            }
        }

        if (!this.level().isClientSide) {
            updateTargetState();
        }

        // 动画状态机更新（基类处理服务端/客户端分支、零姿态恢复）
        tickAnimation();

        if (isAlerting() && !isAlertCompleted()) {
            if (this.level().getGameTime() - lastStateChangeTime > 10) {
                this.entityData.set(DATA_ALERT_COMPLETED, true);
                this.entityData.set(DATA_ALERTING, false);
            }
        }

        // Note: animController.tick() and animController.physTick() are called
        // automatically by Spark-Core's AnimApplier via EntityTickEvent.Post
        // and PhysicsEntityTickEvent. Do NOT call them manually here.
    }

    // ========== Sound Overrides ==========
    //
    // 临时使用 vanilla Zombie 音效。未来替换为 ModSounds 注册的自定义音效（.ogg 资源
    // 由 MobSoundLoader 自动注册，或通过 ContentPack 的 mob 配置 JSON 指定）。
    //
    // getStepSound 非 vanilla override（Mob 没有此方法），由 AnimatedMob 的
    // playStepSound(BlockPos, BlockState) 调用，模仿 Zombie 的模式。

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ZOMBIE_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return SoundEvents.ZOMBIE_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ZOMBIE_DEATH;
    }

    @Override
    public SoundEvent getStepSound() {
        return SoundEvents.ZOMBIE_STEP;
    }

    @Override
    public float getVoicePitch() {
        // vanilla Zombie 默认 0.85f，让声音更低沉
        return super.getVoicePitch() * 0.85f;
    }

    // ========== Attributes ==========
    public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.23)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.FOLLOW_RANGE, 16.0)
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.ATTACK_KNOCKBACK, 1.0)
                .add(Attributes.ARMOR, 2.0);
    }

    // ========== Debug ==========
    public String getCurrentStateInfo() {
        int state = getAnimState();
        String stateName;
        switch (state) {
            case STATE_IDLE: stateName = "idle"; break;
            case STATE_WALKING: stateName = "walking"; break;
            case STATE_RUNNING: stateName = "running"; break;
            case STATE_ATTACK: stateName = "attack"; break;
            case STATE_ALERT: stateName = "alert"; break;
            case STATE_HIT_FRONT: stateName = "hit_front"; break;
            case STATE_HIT_BACK: stateName = "hit_back"; break;
            case STATE_WINDING: stateName = "winding"; break;
            default: stateName = "unknown"; break;
        }
        return String.format("State: %s, Target: %s, Bones: %d",
                stateName,
                hasTarget() ? "yes" : "no",
                boneData.getBoneCount());
    }
}
