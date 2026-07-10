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
import org.joml.Matrix4f;
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
    public static final int STATE_WINDING = 7;
    
    // Server-side bone cache for hit detection
    // position: 骨骼 pivot 的世界坐标（用于快速距离判定）
    // matrix:   骨骼 pivot 的世界变换矩阵（含旋转，用于 OBB 构造）
    private final Map<String, Vector3f> serverBonePositions = new HashMap<>();
    private final Map<String, Matrix4f> serverBoneMatrices = new HashMap<>();
    private long lastBoneUpdateTime = 0;

    public ModularZombie2(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        this.animController = new AnimController(this);
        this.modelController = new ModelController(this);
        // 设置贴图路径: assets/catoxidesbattlerebuild/textures/entity/modular_zombie_2.png
        this.modelController.setTextureLocation(ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "textures/entity/modular_zombie_2.png"));
        LogManager.zombie2Init(getId());
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
        // Update server-side bone cache for hit detection
        // - position: 用于快速距离筛选
        // - matrix:    用于 OBB 构造（含旋转），存的是 pivot 的世界变换矩阵，
        //              其 translation 即 pivot 世界坐标，rotation 即骨骼朝向
        if (!level().isClientSide()) {
            String boneName = event.getBonePose().getName();
            Vector3f worldPos = event.getBonePose().getWorldBonePivot(Vec3.ZERO, 1.0f);
            Matrix4f worldMat = event.getBonePose().getWorldBonePivotMatrix(1.0f);
            serverBonePositions.put(boneName, worldPos);
            serverBoneMatrices.put(boneName, worldMat);
            lastBoneUpdateTime = level().getGameTime();
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
     * 获取服务端骨骼世界变换矩阵（含旋转）
     * <p>矩阵的 translation 部分即骨骼 pivot 的世界坐标，rotation 部分即骨骼朝向。
     * 用于构造骨骼级 OBB 受击盒。
     * @param boneName 骨骼名称
     * @return 骨骼的世界变换矩阵（pivot 空间），未找到返回 null
     */
    public Matrix4f getServerBoneMatrix(String boneName) {
        return serverBoneMatrices.get(boneName);
    }

    /**
     * 获取所有服务端骨骼世界变换矩阵
     * @return 骨骼名称到世界变换矩阵（pivot 空间）的映射
     */
    public Map<String, Matrix4f> getAllServerBoneMatrices() {
        return new HashMap<>(serverBoneMatrices);
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
            LogManager.boneSyncClient(getId(), positions.size());
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
            updateAnimationState();
            // 零姿态检测：如果没有动画在播放且已初始化，则恢复
            // 注意：playAnimation 在物理线程异步执行，需要给宽限期避免误判
            if (serverInitialAnimPlayed && !this.animController.isPlayingAnim()
                    && this.level().getGameTime() - lastAnimRequestTick > ZERO_POSE_GRACE_TICKS) {
                recoverFromZeroPose();
            }
        } else {
            // 客户端：根据同步的状态播放动画
            syncClientAnimation();
            // 客户端也检测零姿态
            if (lastClientAnimState != -1 && !this.animController.isPlayingAnim()
                    && this.level().getGameTime() - lastAnimRequestTick > ZERO_POSE_GRACE_TICKS) {
                recoverFromZeroPose();
            }
        }

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

    private boolean serverInitialAnimPlayed = false;
    private long lastStateChangeTick = 0;
    private static final long MIN_STATE_HOLD_TICKS = 10;

    // 游荡动画滞回：游荡 AI 移动/停止周期约 1-2 秒，会和 walking 动画 length=2s 冲突，
    // 导致动画频繁从 0.0 帧重启，永远播不到迈步时刻（0.5s/1.5s），看起来两腿同步。
    // 进入 WALKING 后必须持续静止 N tick 才切回 IDLE，避免短暂停顿重置动画。
    private long lastMovingTick = 0;
    private static final long WALKING_HOLD_TICKS = 40; // 2 秒，覆盖一个完整步态周期

    // 零姿态检测：playAnimation 在物理线程异步执行，需要给宽限期避免误判
    private long lastAnimRequestTick = 0;
    private static final long ZERO_POSE_GRACE_TICKS = 10;

    private void updateAnimationState() {
        int currentState = getAnimState();
        
        // Skip state updates during hit animation (only while isHit is true)
        if (isHit() && (currentState == STATE_HIT_FRONT || currentState == STATE_HIT_BACK)) {
            return;
        }

        // Play initial idle animation on server side once
        if (!serverInitialAnimPlayed) {
            serverInitialAnimPlayed = true;
            lastStateChangeTick = this.level().getGameTime();
            playAnimationForState(STATE_IDLE);
            return;
        }
        
        int newState = determineAnimationState();
        
        // 只有状态改变时且超过最小保持时间才播放新动画
        if (currentState != newState && (this.level().getGameTime() - lastStateChangeTick >= MIN_STATE_HOLD_TICKS)) {
            setAnimState(newState);
            lastStateChangeTick = this.level().getGameTime();
            playAnimationForState(newState);
            LogManager.zombie2StateChanged(getId(), getStateAnimationName(currentState), getStateAnimationName(newState));
        }
    }
    
    private int lastClientAnimState = -1;

    private void syncClientAnimation() {
        int currentState = getAnimState();

        if (currentState != lastClientAnimState) {
            lastClientAnimState = currentState;
            playAnimationForState(currentState);
            LogManager.zombie2ClientSync(getId(), getStateAnimationName(currentState));
        }
    }

    /**
     * 零姿态恢复：当检测到没有动画在播放时，立即重新请求当前状态的动画
     * 这可以处理因动画冲突、状态机异步处理等原因导致的零姿态问题
     * 注意：由于 playAnimation 在物理线程异步执行，恢复后需要等待宽限期再次检测
     */
    private void recoverFromZeroPose() {
        int currentState = getAnimState();
        String animName = getStateAnimationName(currentState);
        LogManager.zombie2ZeroPoseRecover(this.level().isClientSide, getId(), animName);
        playAnimationForState(currentState);
    }
    
    private int determineAnimationState() {
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
    
    /**
     * 根据状态播放对应的动画
     * 使用 AnimInstance.independentEnter() 来正确启动动画
     * - independentEnter() 会先停止当前组的动画，然后调用 enter()
     * - enter() 会将动画添加到 AnimController 并开始播放流程
     */
    private void playAnimationForState(int state) {
        String animName = getStateAnimationName(state);
        if (animName != null) {
            lastAnimRequestTick = this.level().getGameTime();
            try {
                cn.solarmoon.spark_core.animation.anim.AnimInstance anim =
                    cn.solarmoon.spark_core.animation.anim.AnimInstanceBuilderKt.animInstance(
                        this,
                        animName,
                        true,
                        (animInstance) -> {
                            animInstance.setInTransitionTime(0.05f);
                            animInstance.setOutTransitionTime(0.05f);
                            return null;
                        }
                    );

                if (anim != null) {
                    anim.independentEnter();
                    LogManager.zombie2AnimStarted(getId(), animName, String.valueOf(anim.getState()));
                } else {
                    LogManager.zombie2AnimFailed(getId(), animName);
                }
            } catch (Exception e) {
                LogManager.zombie2AnimError(getId(), animName, e.getMessage(), e);
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
            case STATE_WINDING: return "aggressive";
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
            case STATE_WINDING: stateName = "winding"; break;
            default: stateName = "unknown"; break;
        }
        return String.format("State: %s, Target: %s, Bones: %d", 
                stateName, 
                hasTarget() ? "yes" : "no", 
                serverBonePositions.size());
    }
}