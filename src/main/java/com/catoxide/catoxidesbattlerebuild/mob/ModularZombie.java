package com.catoxide.catoxidesbattlerebuild.mob;

import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

public class ModularZombie extends Zombie implements GeoEntity {
    // 状态变量
    private int hitTime = 0;
    private LivingEntity lastTarget = null;
    private double lastDistanceToTarget = 0;
    private long lastStateChangeTime = 0;

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

    public ModularZombie(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        this.aiManager = new AIManager(this);
        this.animationController = new ZombieAnimationController(this);
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

    public double getLastDistanceToTarget() {
        return lastDistanceToTarget;
    }

    public void setLastDistanceToTarget(double distance) {
        this.lastDistanceToTarget = distance;
    }

    // 更新目标状态
    private void updateTargetState() {
        if (isHit()) {
            return;
        }

        LivingEntity currentTarget = this.getTarget();
        boolean previousHasTarget = hasTarget();

        boolean newHasTarget = currentTarget != null &&
                currentTarget.isAlive() &&
                hasAggressiveTarget();

        if (newHasTarget != previousHasTarget) {
            this.entityData.set(DATA_HAS_TARGET, newHasTarget);

            if (newHasTarget) {
                this.entityData.set(DATA_ALERTING, true);
                this.entityData.set(DATA_ALERT_COMPLETED, false);
                lastStateChangeTime = this.level().getGameTime();
                System.out.println("目标发现！进入警戒状态");
            } else {
                this.entityData.set(DATA_ALERTING, false);
                this.entityData.set(DATA_ALERT_COMPLETED, false);
                System.out.println("目标丢失！重置状态");
            }
        }

        lastTarget = currentTarget;
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

    // 修改 hurt 方法
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isDeadOrDying()) {
            return false;
        }

        boolean hurt = super.hurt(source, amount);

        if (hurt) {
            if (aiManager != null) {
                aiManager.stopWindUp();
            }

            if (this.getHealth() <= 0.0F) {
                System.out.println("实体死亡，血量: " + this.getHealth());
                resetAllStates();
                return true;
            }

            // 设置受击状态
            this.entityData.set(DATA_IS_HIT, true);
            hitTime = 25;
            animationController.triggerHit();

            this.getNavigation().stop();
            System.out.println("受到伤害，进入受击状态，剩余血量: " + this.getHealth());
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

        System.out.println("死亡状态重置完成");
    }

    // 在 tick 方法中统一调试输出
    @Override
    public void tick() {
        super.tick();

        if (this.isDeadOrDying()) return;

        // 更新距离跟踪
        LivingEntity target = this.getTarget();
        if (target != null) {
            setLastDistanceToTarget(this.distanceTo(target));
        } else {
            setLastDistanceToTarget(0);
        }

        // 检查寻路状态
        if (!this.level().isClientSide && hasTarget() && this.tickCount % 40 == 0) {
            if (target != null) {
                PathNavigation navigation = this.getNavigation();
                boolean hasPath = navigation.getPath() != null;
                boolean isDone = navigation.isDone();

                System.out.printf("寻路状态检查 | 有路径: %s | 导航完成: %s | 目标距离: %.2f%n",
                        hasPath, isDone, this.distanceTo(target));

                // 如果应该有路径但没有，强制重新计算
                if (!hasPath && !isDone && this.distanceTo(target) > 3.0) {
                    System.out.println("强制重新计算路径");
                    navigation.moveTo(target, 1.0D);
                }
            }
        }

        // 同步攻击前摇状态
        if (!this.level().isClientSide) {
            boolean isWindingUp = aiManager.isWindingUp();
            if (this.isWindingUp() != isWindingUp) {
                this.entityData.set(DATA_WINDUP, isWindingUp);
            }

            // 确保攻击前摇更新被调用
            aiManager.updateWindUp();
        }

        // 受击状态期间暂停其他状态更新
        if (!isHit()) {
            updateTargetState();
        }

        // 更新受击状态计时器
        if (isHit()) {
            hitTime--;
            if (hitTime <= 0) {
                this.entityData.set(DATA_IS_HIT, false);
                System.out.println("受击状态结束");
            }
        }

        // 更新警戒完成状态
        if (isAlerting() && !isAlertCompleted()) {
            if (this.level().getGameTime() - lastStateChangeTime > 15) {
                this.entityData.set(DATA_ALERT_COMPLETED, true);
                this.entityData.set(DATA_ALERTING, false);
                System.out.println("警戒动画完成");
            }
        }

        // 更新动画控制器
        animationController.tick();

        // 更新 AI 管理器
        if (!this.level().isClientSide) {
            aiManager.tick();
        }

        // 统一调试输出
        if (!this.level().isClientSide && this.tickCount % 80 == 0) {
            debugBehaviorState();
        }
    }

    // 调试方法
    private void debugBehaviorState() {
        if (this.level().isClientSide) {
            return;
        }

        System.out.printf("行为状态调试 [Tick: %d] | 目标: %s | 目标实体: %s | 警戒中: %s | 警戒完成: %s | 受击中: %s | 移动: %s | AI状态: %s | 动画状态: %s%n",
                this.tickCount,
                hasTarget(),
                target != null ? target.getName().getString() : "null",
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
}