package com.catoxide.catoxidesbattlerebuild.mob.zombie2;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import cn.solarmoon.spark_core.animation.IEntityAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimController;
import cn.solarmoon.spark_core.animation.model.ModelController;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import cn.solarmoon.spark_core.event.BoneUpdateEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

public class ModularZombie2 extends Zombie implements IEntityAnimatable<ModularZombie2> {
    
    private int hitTime = 0;
    private long lastStateChangeTime = 0;

    // Spark-Core animation system
    private final AnimController animController;
    private final ModelController modelController;
    
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
    
    // Server-side bone position cache for hit detection
    private final Map<String, Vector3f> serverBonePositions = new HashMap<>();
    private long lastBoneUpdateTime = 0;

    public ModularZombie2(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        this.animController = new AnimController(this);
        this.modelController = new ModelController(this);
        // 设置贴图路径: assets/catoxidesbattlerebuild/textures/entity/modular_zombie_2.png
        this.modelController.setTextureLocation(ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "textures/entity/modular_zombie_2.png"));
        LogManager.aiDebug(String.valueOf(getId()), "ModularZombie2 initialized with Spark-Core");
    }

    // ========== IEntityAnimatable Implementation ==========
    @Override
    public ModularZombie2 getAnimatable() {
        return this;
    }

    @Override
    public AnimController getAnimController() {
        return animController;
    }

    @Override
    public ModelController getModelController() {
        return modelController;
    }

    @Override
    public Level getAnimLevel() {
        return this.level();
    }

    @Override
    public ModelIndex getDefaultModelIndex() {
        // 正确格式: ModelIndex(type="entity", location="catoxidesbattlerebuild:modular_zombie_2")
        return new ModelIndex(
            "entity",  // type: 模型类型 (entity 或 item)
            ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "modular_zombie_2")  // location
        );
    }

    @Override
    public void onBoneUpdate(BoneUpdateEvent event) {
        // Update server-side bone positions for hit detection
        if (!level().isClientSide()) {
            String boneName = event.getBonePose().getName();
            Vector3f worldPos = event.getBonePose().getWorldBonePivot(Vec3.ZERO, 1.0f);
            serverBonePositions.put(boneName, worldPos);
            lastBoneUpdateTime = level().getGameTime();
            LogManager.serverDebug("BoneUpdate", "Updated bone {} position: {}", boneName, worldPos);
        }
    }

    // ========== Public API for Server-Side Bone Access ==========
    
    /**
     * 获取服务端骨骼世界位置
     * @param boneName 骨骼名称
     * @return 骨骼的世界坐标，如果未找到返回null
     */
    public Vector3f getServerBonePosition(String boneName) {
        return serverBonePositions.get(boneName);
    }

    /**
     * 获取所有服务端骨骼位置
     * @return 骨骼名称到世界坐标的映射
     */
    public Map<String, Vector3f> getAllServerBonePositions() {
        return new HashMap<>(serverBonePositions);
    }

    /**
     * 检查骨骼位置数据是否最新（最近5tick内更新）
     */
    public boolean isBoneDataUpToDate() {
        return level().getGameTime() - lastBoneUpdateTime <= 5;
    }

    /**
     * 从服务端同步骨骼位置到客户端
     * @param positions 骨骼名称到世界坐标的映射
     */
    public void updateClientBonePositions(Map<String, Vector3f> positions) {
        if (level().isClientSide()) {
            serverBonePositions.clear();
            serverBonePositions.putAll(positions);
            lastBoneUpdateTime = level().getGameTime();
            LogManager.clientDebug("BoneSync", "Updated client bone positions for entity {}", getId());
        }
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

    public int getAnimState() {
        return this.entityData.get(DATA_ANIM_STATE);
    }

    public void setAnimState(int state) {
        this.entityData.set(DATA_ANIM_STATE, state);
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

        if (isHit()) {
            hitTime--;
            if (hitTime <= 0) {
                this.entityData.set(DATA_IS_HIT, false);
                setAnimState(STATE_IDLE);
            }
        }

        if (!this.level().isClientSide) {
            updateTargetState();
            updateAnimationState();
        }

        if (isAlerting() && !isAlertCompleted()) {
            if (this.level().getGameTime() - lastStateChangeTime > 10) {
                this.entityData.set(DATA_ALERT_COMPLETED, true);
                this.entityData.set(DATA_ALERTING, false);
            }
        }

        // Spark-Core animation tick
        animController.tick();
        if (!level().isClientSide()) {
            animController.physTick();
        }
    }

    private void updateAnimationState() {
        int currentState = getAnimState();
        
        // Skip state updates during hit animation
        if (currentState == STATE_HIT_FRONT || currentState == STATE_HIT_BACK) {
            return;
        }
        
        LivingEntity target = this.getTarget();
        int newState = STATE_IDLE;
        
        if (target != null && target.isAlive()) {
            double distance = this.distanceTo(target);
            double speed = this.getAttributeValue(Attributes.MOVEMENT_SPEED);
            
            if (this.swinging || isWindingUp()) {
                newState = STATE_ATTACK;
            } else if (speed > 0.15 || this.isSprinting()) {
                newState = STATE_RUNNING;
            } else if (distance < 5.0) {
                newState = STATE_ALERT;
            } else if (!this.getNavigation().isDone()) {
                newState = STATE_WALKING;
            } else {
                newState = STATE_IDLE;
            }
        } else {
            if (!this.getNavigation().isDone()) {
                newState = STATE_WALKING;
            } else {
                newState = STATE_IDLE;
            }
        }
        
        // 只有状态改变时才播放新动画
        if (currentState != newState) {
            setAnimState(newState);
            playAnimationForState(newState);
        }
    }
    
    /**
     * 根据状态播放对应的动画
     */
    private void playAnimationForState(int state) {
        // 先停止当前所有动画
        animController.stopAllAnimation();
        
        // 根据状态播放新动画
        String animName = getStateAnimationName(state);
        if (animName != null) {
            try {
                // 使用星火核心的 animInstance 函数创建动画实例
                cn.solarmoon.spark_core.animation.anim.AnimInstance anim = 
                    cn.solarmoon.spark_core.animation.anim.AnimInstanceBuilderKt.animInstance(
                        this, 
                        animName, 
                        true, 
                        (animInstance) -> {
                            // 设置混合过渡时间
                            animInstance.setInTransitionTime(0.2f);
                            return null; // 返回 null 以匹配 Kotlin 的 Unit 返回类型
                        }
                    );
                
                if (anim != null) {
                    animController.playAnimation(anim);
                    LogManager.aiDebug(String.valueOf(getId()), "Playing animation: " + animName);
                }
            } catch (Exception e) {
                LogManager.aiDebug(String.valueOf(getId()), "Failed to play animation: " + e.getMessage());
            }
        }
    }
    
    /**
     * 获取状态对应的动画名称
     */
    private String getStateAnimationName(int state) {
        switch (state) {
            case STATE_IDLE: return "still";
            case STATE_WALKING: return "walking";
            case STATE_RUNNING: return "running";
            case STATE_ATTACK: return "attack";
            case STATE_ALERT: return "alert";
            case STATE_HIT_FRONT: return "hit_front";
            case STATE_HIT_BACK: return "hit_back";
            default: return "still";
        }
    }

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
            default: stateName = "unknown"; break;
        }
        return String.format("State: %s, Target: %s, Bones: %d", 
                stateName, 
                hasTarget() ? "yes" : "no", 
                serverBonePositions.size());
    }
}