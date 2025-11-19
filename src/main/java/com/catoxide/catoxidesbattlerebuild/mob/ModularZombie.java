package com.catoxide.catoxidesbattlerebuild.mob;

import com.catoxide.catoxidesbattlerebuild.client.model.ModularZombieModel;
import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.BodyPartHealthSystem;
import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.BodyPartManager;
import com.catoxide.catoxidesbattlerebuild.mob.server.ServerAnimationSystem;
import com.catoxide.catoxidesbattlerebuild.network.HitboxRemovePacket;
import com.catoxide.catoxidesbattlerebuild.network.NetworkHandler;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.util.GeckoLibUtil;

public class ModularZombie extends Zombie implements GeoEntity {
    // 状态变量
    private int hitTime = 0;
    private LivingEntity lastTarget = null;
    private double lastDistanceToTarget = 0;
    private long lastStateChangeTime = 0;
    private final GeoModel<ModularZombie> model = new ModularZombieModel();
    private final ServerAnimationSystem serverAnimationSystem;

    // 动画控制器
    private final ZombieAnimationController animationController;

    // 双端同步
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
    // 怪物AI
    private AIManager aiManager;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    // 模块血量系统 - 新增精确碰撞系统
    private final BodyPartManager bodyPartManager;
    private final BodyPartHealthSystem healthSystem;

    public ModularZombie(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        this.aiManager = new AIManager(this);
        this.animationController = new ZombieAnimationController(this);
        this.bodyPartManager = new BodyPartManager(this);
        this.healthSystem = new BodyPartHealthSystem(this, bodyPartManager);
        this.serverAnimationSystem = new ServerAnimationSystem(this);
    }

    public AIManager getAIManager() {
        if (this.aiManager == null) {
            System.out.println("警告: AIManager 为 null，重新创建");
            this.aiManager = new AIManager(this);
        }
        return this.aiManager;
    }

    public ZombieAnimationController getAnimationController() {
        return animationController;
    }

    // AI管理
    @Override
    protected void registerGoals() {
        // 移除原版攻击AI
        this.targetSelector.removeAllGoals(goal -> true);
        this.goalSelector.removeAllGoals(goal -> true);

        // 基础移动AI
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SmartAttackGoal(this));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));

        // 智能目标选择
        this.targetSelector.addGoal(1, new SmartHurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new SmartNearestAttackableTargetGoal(this, Player.class, true));
    }
    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        // 在实体添加到世界后生成碰撞箱
        if (!this.level().isClientSide) {
            this.bodyPartManager.spawnHitboxEntities();
        }
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
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_WINDUP, false);
        this.entityData.define(DATA_ALERTING, false);
        this.entityData.define(DATA_ALERT_COMPLETED, false);
        this.entityData.define(DATA_HAS_TARGET, false);
        this.entityData.define(DATA_IS_HIT, false);
    }

    // 状态获取方法
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

            // 如果不在攻击范围内且导航停止了，重新启动
            if (distance > (attackRange * attackRange) && this.getNavigation().isDone()) {
                this.getNavigation().moveTo(target, 1.0D);
            }
        }
    }
    // 添加其他可能需要的方法
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
    // 简化 updateTargetState 方法
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

    // 攻击性目标检测
    private boolean hasAggressiveTarget() {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }

        double followRange = this.getAttributeValue(Attributes.FOLLOW_RANGE);
        double distance = this.distanceTo(target);

        // 基础距离检测
        if (distance > followRange + 5.0) {
            return false;
        }

        // 视觉检测 - 有直接视线
        if (this.getSensing().hasLineOfSight(target)) {
            return true;
        }

        // 听觉检测 - 只有对玩家有效
        if (target instanceof Player player) {
            return getAIManager().canHearTarget(player);
        }

        return false;
    }


    public boolean hurt(DamageSource source, float amount) {
        if (this.isDeadOrDying()) {
            return false;
        }

        boolean hurt = super.hurt(source, amount);

        if (hurt) {
            // 通知 AI 管理器受到伤害
            if (aiManager != null) {
                aiManager.onHurt();
            }

            if (this.getHealth() <= 0.0F) {
                resetAllStates();
                return true;
            }

            // 设置受击状态 - 确保在客户端和服务端都同步
            this.entityData.set(DATA_IS_HIT, true);
            hitTime = 20; // 增加到20ticks，确保动画能完整播放
            animationController.triggerHit();

            // 添加调试输出
            System.out.println("受到伤害，触发受击动画，hitTime=" + hitTime);

            // 停止当前移动
            this.getNavigation().stop();
        }

        return hurt;
    }

    // 重置所有状态
    private void resetAllStates() {
        this.entityData.set(DATA_IS_HIT, false);
        this.entityData.set(DATA_ALERTING, false);
        this.entityData.set(DATA_ALERT_COMPLETED, false);
        this.entityData.set(DATA_HAS_TARGET, false);
        this.entityData.set(DATA_WINDUP, false);

        animationController.reset();

        this.goalSelector.removeAllGoals(goal -> true);
        this.targetSelector.removeAllGoals(goal -> true);
        bodyPartManager.discardAllHitboxes();
        if (!this.level().isClientSide) {
            NetworkHandler.sendToAllTracking(new HitboxRemovePacket(this.getId()), this);
        }

        System.out.println("死亡状态重置完成");
    }


    // 简化 tick 方法
    @Override
    public void tick() {
        super.tick();

        if (this.isDeadOrDying()) return;

        // 更新受击状态计时器 - 这个应该在所有状态之前检查
        if (isHit()) {
            hitTime--;
            if (hitTime <= 0) {
                this.entityData.set(DATA_IS_HIT, false);
                System.out.println("受击状态结束");
            }
        }

        // 同步攻击前摇状态 - 只在服务端更新
        if (!this.level().isClientSide) {
            boolean isWindingUp = aiManager.isWindingUp();
            if (this.isWindingUp() != isWindingUp) {
                setWindingUp(isWindingUp);
            }
        }

        // 受击状态期间暂停其他状态更新
        if (!isHit()) {
            updateTargetState();
        }

        // 更新警戒完成状态
        if (isAlerting() && !isAlertCompleted()) {
            if (this.level().getGameTime() - lastStateChangeTime > 10) {
                this.entityData.set(DATA_ALERT_COMPLETED, true);
                this.entityData.set(DATA_ALERTING, false);
            }
        }

        // 更新动画控制器
        animationController.tick();


        // 更新 AI 管理器 - 只在服务端
        if (!this.level().isClientSide) {
            aiManager.tick();
            // 新增：更新精确碰撞箱位置
            bodyPartManager.updateHitboxPositions();
            serverAnimationSystem.serverTick();
            bodyPartManager.syncAllToClient();
        }

        // 调试输出 - 每100tick输出一次
        if (this.tickCount % 100 == 0) {
            debugBehaviorState();
        }
    }
    // 新增：获取模块血量系统
    public BodyPartManager getBodyPartManager() {
        return bodyPartManager;
    }

    public BodyPartHealthSystem getHealthSystem() {
        return healthSystem;
    }

    // 新增：处理部位伤害
    public void onPartHit(String partName, float damage) {
        healthSystem.onPartHit(partName, damage);
    }
    // 调试方法
    private void debugBehaviorState() {
        if (this.level().isClientSide) {
            return;
        }

        LivingEntity target = this.getTarget(); // 使用 getTarget() 方法

        System.out.printf("行为状态调试 [Tick: %d] | 目标: %s | 目标实体: %s | 警戒中: %s | 警戒完成: %s | 受击中: %s | 移动: %s | AI状态: %s | 动画状态: %s%n",
                this.tickCount,
                hasTarget(),
                target != null ? target.getName().getContents() : "null",
                isAlerting(),
                isAlertCompleted(),
                isHit(),
                this.getDeltaMovement().horizontalDistanceSqr() > 0.001,
                aiManager != null ? "正常" : "null",
                animationController.getAnimationStateInfo());
    }

    // 获取当前状态信息
    public String getCurrentStateInfo() {
        return animationController.getAnimationStateInfo();
    }
    // 在 ModularZombie 类中添加这个方法


    // 如果需要，添加获取模型的方法（根据你的 GeckoLib 实现）
    public software.bernie.geckolib.core.animatable.GeoAnimatable getAnimatable() {
        return this;
    }

    // 修正 getModel() 方法 - 返回 BakedGeoModel
    public BakedGeoModel getModel(AnimationState<ModularZombie> state) {
        return this.model.getBakedModel(this.model.getModelResource(this));
    }

    // 获取 GeoModel
    public GeoModel<ModularZombie> getModel() {
        return model;
    }
    public ServerAnimationSystem getServerAnimationSystem() {
        return serverAnimationSystem;
    }
    public void handleDebugCommand(String command) {
        BodyPartManager manager = getBodyPartManager();
        if (manager == null) return;

        switch (command.toLowerCase()) {
            case "debug_position":
                manager.skipPositionTransforms();
                break;
            case "debug_rotation":
                manager.skipRotationTransforms();
                break;
            case "debug_pivot":
                manager.skipPivotTransforms();
                break;
            case "debug_all":
                manager.skipAllTransforms();
                break;
            case "debug_enable":
                manager.enableDebugMode();
                break;
            case "debug_status":
                System.out.println(manager.getTransformDebugInfo());
                break;
        }
    }
}