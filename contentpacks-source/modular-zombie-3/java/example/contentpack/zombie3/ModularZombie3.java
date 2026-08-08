package example.contentpack.zombie3;

import cn.solarmoon.spark_core.animation.model.ModelIndex;
import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
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
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * ModularZombie3：从主 mod 剥离的独立 ContentPack 生物
 * <p>功能与 ModularZombie2 完全相同，但作为 ContentPack 模块加载。
 * 用于验证 ContentPack 框架的正确性。
 */
public class ModularZombie3 extends AnimatedMob<ModularZombie3> {

    private int hitTime = 0;
    private long lastStateChangeTime = 0;

    // Synced entity data
    private static final EntityDataAccessor<Boolean> DATA_WINDUP =
            SynchedEntityData.defineId(ModularZombie3.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_ALERTING =
            SynchedEntityData.defineId(ModularZombie3.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_ALERT_COMPLETED =
            SynchedEntityData.defineId(ModularZombie3.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_HAS_TARGET =
            SynchedEntityData.defineId(ModularZombie3.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_IS_HIT =
            SynchedEntityData.defineId(ModularZombie3.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ANIM_STATE =
            SynchedEntityData.defineId(ModularZombie3.class, EntityDataSerializers.INT);

    // Animation states
    public static final int STATE_IDLE = 0;
    public static final int STATE_WALKING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_ATTACK = 3;
    public static final int STATE_ALERT = 4;
    public static final int STATE_HIT_FRONT = 5;
    public static final int STATE_HIT_BACK = 6;
    public static final int STATE_WINDING = 7;

    private long lastMovingTick = 0;
    private static final long WALKING_HOLD_TICKS = 40;

    public ModularZombie3(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        LogManager.serverInfo("Zombie3", "Entity {} initialized (ContentPack)", getId());
    }

    // ========== AnimatedMob 抽象方法实现 ==========

    @Override
    public ModelIndex getDefaultModelIndex() {
        return new ModelIndex(
            "entity",
            ResourceLocation.fromNamespaceAndPath("zombie3pack", "modular_zombie_3")
        );
    }

    @Override
    public ResourceLocation getTextureLocation() {
        return ResourceLocation.fromNamespaceAndPath("zombie3pack", "textures/entity/modular_zombie_3.png");
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
        boolean isMoving = this.moveControl.hasWanted() || !this.getNavigation().isDone();
        LivingEntity target = this.getTarget();

        if (isMoving) {
            lastMovingTick = this.level().getGameTime();
        }

        // 挥动保持窗口（基类辅助）：挥空后 attack 动画播完再回落，避免状态机卡死
        if (isAttackStateActive()) return STATE_ATTACK;
        if (isWindingUp()) return STATE_WINDING;
        if (isAlerting() && !isAlertCompleted()) return STATE_ALERT;

        boolean isAlert = hasTarget() || isAlertCompleted();
        if (isAlert) {
            return isMoving ? STATE_RUNNING : STATE_ALERT;
        } else {
            if (!isMoving && getAnimState() == STATE_WALKING) {
                long idleSince = this.level().getGameTime() - lastMovingTick;
                if (idleSince < WALKING_HOLD_TICKS) return STATE_WALKING;
            }
            return isMoving ? STATE_WALKING : STATE_IDLE;
        }
    }

    @Override
    protected boolean shouldUpdateState(int currentState) {
        if (isHit() && (currentState == STATE_HIT_FRONT || currentState == STATE_HIT_BACK)) {
            return false;
        }
        return true;
    }

    // ========== Goal Registration ==========
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 固定攻击间隔（20tick），避免裸 MeleeAttackGoal 的 6/ATTACK_SPEED=2tick 疯狂挥动
        this.goalSelector.addGoal(1, new com.catoxide.catoxidesbattlerebuild.core.mob.FixedIntervalAttackGoal(this, 1.0D, false, 20));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ========== Synced Data ==========
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
    public boolean hasTarget() { return entityData.get(DATA_HAS_TARGET); }
    public boolean isAlerting() { return entityData.get(DATA_ALERTING); }
    public boolean isAlertCompleted() { return entityData.get(DATA_ALERT_COMPLETED); }
    public boolean isHit() { return entityData.get(DATA_IS_HIT); }
    public boolean isWindingUp() { return entityData.get(DATA_WINDUP); }
    public boolean isAttacking() { return isWindingUp() || swinging; }
    public boolean isInCombatState() { return hasTarget() && (isWindingUp() || isAlerting()); }
    public void setWindingUp(boolean windingUp) { entityData.set(DATA_WINDUP, windingUp); }

    // ========== Target State ==========
    private void updateTargetState() {
        if (isHit()) return;
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
        if (this.isDeadOrDying()) return false;
        boolean hurt = super.hurt(source, amount);
        if (hurt) {
            if (this.getHealth() <= 0.0F) { resetAllStates(); return true; }
            // 受击免疫：高频受击时不重置受击状态，避免被锁死在受击姿态
            if (!isHit() || hitTime <= 5) {
                this.entityData.set(DATA_IS_HIT, true);
                hitTime = 10;   // 受击动画 0.5 秒
                setAnimState(determineHitAnimation(source));
            }
            // 不再 navigation.stop()：受击不打断 AI/移动
        }
        return hurt;
    }

    private int determineHitAnimation(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker == null) return getRandom().nextBoolean() ? STATE_HIT_FRONT : STATE_HIT_BACK;
        double dx = attacker.getX() - this.getX();
        double dz = attacker.getZ() - this.getZ();
        float attackerAngle = (float) Math.toDegrees(Math.atan2(dz, dx));
        float angleDiff = normalizeAngle(attackerAngle - this.getYRot());
        return (angleDiff >= -90 && angleDiff <= 90) ? STATE_HIT_BACK : STATE_HIT_FRONT;
    }

    private float normalizeAngle(float angle) {
        while (angle > 180) angle -= 360;
        while (angle <= -180) angle += 360;
        return angle;
    }

    private void resetAllStates() {
        entityData.set(DATA_IS_HIT, false);
        entityData.set(DATA_ALERTING, false);
        entityData.set(DATA_ALERT_COMPLETED, false);
        entityData.set(DATA_HAS_TARGET, false);
        entityData.set(DATA_WINDUP, false);
        entityData.set(DATA_ANIM_STATE, STATE_IDLE);
    }

    // ========== Tick ==========
    @Override
    public void tick() {
        super.tick();
        if (this.isDeadOrDying()) return;
        if (this.isSprinting()) this.setSprinting(false);

        if (isHit()) {
            hitTime--;
            if (hitTime <= 0) entityData.set(DATA_IS_HIT, false);
        }
        if (!this.level().isClientSide) updateTargetState();
        tickAnimation();

        if (isAlerting() && !isAlertCompleted()) {
            if (this.level().getGameTime() - lastStateChangeTime > 10) {
                entityData.set(DATA_ALERT_COMPLETED, true);
                entityData.set(DATA_ALERTING, false);
            }
        }
    }

    // ========== Sounds (temp vanilla) ==========
    @Override protected SoundEvent getAmbientSound() { return SoundEvents.ZOMBIE_AMBIENT; }
    @Override protected SoundEvent getHurtSound(DamageSource s) { return SoundEvents.ZOMBIE_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.ZOMBIE_DEATH; }
    @Override public SoundEvent getStepSound() { return SoundEvents.ZOMBIE_STEP; }
    @Override public float getVoicePitch() { return super.getVoicePitch() * 0.85f; }

    // ========== Attributes ==========
    public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.23)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.FOLLOW_RANGE, 16.0)
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.ATTACK_KNOCKBACK, 1.0)
                .add(Attributes.ARMOR, 2.0)
                .add(Attributes.ATTACK_SPEED, 4.0); // 缺失会导致 swinging 永不重置 -> 动画卡死
    }
}
