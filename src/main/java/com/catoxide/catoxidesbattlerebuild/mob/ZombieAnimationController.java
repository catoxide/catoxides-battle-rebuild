package com.catoxide.catoxidesbattlerebuild.mob;

import com.catoxide.catoxidesbattlerebuild.mob.server.ServerGeoModelLoader;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.loading.json.raw.Bone;

public class ZombieAnimationController {
    private final ModularZombie zombie;

    // 动画状态跟踪
    private String currentAnimation = "";
    private boolean animationLocked = false;
    private int animationLockTime = 0;
    //private final ServerGeoModelLoader geoModelLoader;

    // 动画常量
    private static final RawAnimation IDLE_ANIMATION = RawAnimation.begin().thenLoop("animation.zombie.still");
    private static final RawAnimation WALKING_ANIMATION = RawAnimation.begin().thenLoop("animation.zombie.walking");
    private static final RawAnimation AGGRESSIVE_ANIMATION = RawAnimation.begin().thenLoop("animation.zombie.aggressive");
    private static final RawAnimation RUNNING_ANIMATION = RawAnimation.begin().thenLoop("animation.zombie.running");
    private static final RawAnimation ALERT_ANIMATION = RawAnimation.begin().thenPlay("animation.zombie.alert");
    private static final RawAnimation ATTACK_ANIMATION = RawAnimation.begin().thenPlay("animation.zombie.attack");
    private static final RawAnimation HIT_FRONT_ANIMATION = RawAnimation.begin().thenPlay("animation.zombie.hit_front");
    private static final RawAnimation HIT_BACK_ANIMATION = RawAnimation.begin().thenPlay("animation.zombie.hit_back");

    public ZombieAnimationController(ModularZombie zombie) {
        this.zombie = zombie;

    }

    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 只有一个主控制器处理所有动画
        controllers.add(new AnimationController<>(zombie, "MainController", 5, this::mainControllerPredicate));
    }

    // 主控制器 - 处理所有动画状态
    private PlayState mainControllerPredicate(AnimationState<ModularZombie> state) {
        if (zombie.isDeadOrDying()) {
            return PlayState.STOP;
        }

        // 动画状态优先级（从高到低）：
        // 1. 受击动画
        // 2. 攻击动画
        // 3. 攻击前摇/攻击后冷却
        // 4. 警戒动画
        // 5. 移动动画
        // 6. 空闲动画

        // 1. 受击状态 - 最高优先级
        if (zombie.isHit()) {
            return handleHitAnimation(state);
        }

        // 2. 攻击动画 - 第二优先级
        if (zombie.swinging) {
            return handleAttackAnimation(state);
        }

        // 3. 攻击前摇和攻击后冷却状态
        if (zombie.isWindingUp() || zombie.isInPostAttackCooldown()) {
            return handleCombatAnimation(state);
        }

        // 4. 警戒动画
        if (zombie.isAlerting() && !zombie.isAlertCompleted()) {
            return handleAlertAnimation(state);
        }

        // 5. 有目标状态下的移动动画
        if (zombie.hasTarget() && zombie.isAlertCompleted()) {
            return handleTargetMovementAnimation(state);
        }

        // 6. 无目标状态下的移动动画
        return handleIdleMovementAnimation(state);
    }

    // 处理受击动画
    private PlayState handleHitAnimation(AnimationState<ModularZombie> state) {
        animationLocked = true;
        animationLockTime = 20;

        RawAnimation hitAnim = zombie.getRandom().nextBoolean() ? HIT_FRONT_ANIMATION : HIT_BACK_ANIMATION;

        if (!"hit".equals(currentAnimation)) {
            currentAnimation = "hit";
            System.out.println("播放受击动画: " + (hitAnim == HIT_FRONT_ANIMATION ? "正面" : "背面"));
            return state.setAndContinue(hitAnim);
        }

        return PlayState.CONTINUE;
    }

    // 处理攻击动画
    private PlayState handleAttackAnimation(AnimationState<ModularZombie> state) {
        animationLocked = true;
        animationLockTime = 20;

        if (!"attack".equals(currentAnimation)) {
            currentAnimation = "attack";
            System.out.println("播放攻击动画");
            return state.setAndContinue(ATTACK_ANIMATION);
        }

        return PlayState.CONTINUE;
    }

    // 处理战斗相关动画（攻击前摇、攻击后冷却）
    private PlayState handleCombatAnimation(AnimationState<ModularZombie> state) {
        if (!"aggressive".equals(currentAnimation)) {
            currentAnimation = "aggressive";
            System.out.println("切换到战斗姿态动画");
            return state.setAndContinue(AGGRESSIVE_ANIMATION);
        }

        return PlayState.CONTINUE;
    }

    // 处理警戒动画
    private PlayState handleAlertAnimation(AnimationState<ModularZombie> state) {
        if (!"alert".equals(currentAnimation)) {
            currentAnimation = "alert";
            System.out.println("播放警戒动画");
            return state.setAndContinue(ALERT_ANIMATION);
        }

        return PlayState.CONTINUE;
    }

    // 处理有目标时的移动动画
    private PlayState handleTargetMovementAnimation(AnimationState<ModularZombie> state) {
        if (shouldMoveToTarget()) {
            // 需要移动 - 播放奔跑动画
            if (!"running".equals(currentAnimation)) {
                currentAnimation = "running";
                System.out.println("切换到奔跑动画");
                return state.setAndContinue(RUNNING_ANIMATION);
            }
        } else {
            // 不需要移动 - 保持战斗姿态
            if (!"aggressive".equals(currentAnimation)) {
                currentAnimation = "aggressive";
                System.out.println("切换到战斗待机动画");
                return state.setAndContinue(AGGRESSIVE_ANIMATION);
            }
        }

        return PlayState.CONTINUE;
    }

    // 处理空闲和移动动画
    private PlayState handleIdleMovementAnimation(AnimationState<ModularZombie> state) {
        if (zombie.getDeltaMovement().horizontalDistanceSqr() > 0.001) {
            // 有移动 - 播放行走动画
            if (!"walking".equals(currentAnimation)) {
                currentAnimation = "walking";
                System.out.println("切换到行走动画");
                return state.setAndContinue(WALKING_ANIMATION);
            }
        } else {
            // 无移动 - 播放空闲动画
            if (!"idle".equals(currentAnimation)) {
                currentAnimation = "idle";
                System.out.println("切换到空闲动画");
                return state.setAndContinue(IDLE_ANIMATION);
            }
        }

        return PlayState.CONTINUE;
    }

    // 判断是否需要向目标移动
    private boolean shouldMoveToTarget() {
        if (!zombie.hasTarget()) {
            return false;
        }

        LivingEntity target = zombie.getTarget();
        if (target == null) {
            return false;
        }

        // 检查距离 - 如果目标在攻击范围内，不需要移动
        double distance = zombie.distanceTo(target);
        double attackRange = zombie.getAIManager().getCurrentAttackRange();

        if (distance <= attackRange) {
            return false;
        }

        // 检查是否有有效的路径
        PathNavigation navigation = zombie.getNavigation();
        boolean hasPath = navigation.getPath() != null && !navigation.isDone();

        return hasPath || distance > attackRange + 1.0;
    }

    // 更新动画状态
    public void tick() {
        // 更新动画锁
        if (animationLocked) {
            animationLockTime--;
            if (animationLockTime <= 0) {
                animationLocked = false;
            }
        }
    }

    // 触发受击动画
    public void triggerHit() {
        animationLocked = true;
        animationLockTime = 20;
    }

    // 重置动画状态
    public void reset() {
        currentAnimation = "idle";
        animationLocked = false;
    }

    // 获取当前动画状态信息
    public String getAnimationStateInfo() {
        if (zombie.isHit()) return "受击状态";
        if (zombie.swinging) return "攻击状态";
        if (zombie.isWindingUp()) return "攻击前摇";
        if (zombie.isInPostAttackCooldown()) return "攻击后冷却";
        if (zombie.isAlerting() && !zombie.isAlertCompleted()) return "警戒中";
        if (zombie.hasTarget() && zombie.isAlertCompleted()) {
            return shouldMoveToTarget() ? "追击目标" : "警戒待机";
        }
        if (zombie.getDeltaMovement().horizontalDistanceSqr() > 0.001) return "随机移动";
        return "空闲状态";
    }

    // 调试方法
    public void debugAnimationState() {
        if (zombie.level().isClientSide) {
            return;
        }

        System.out.printf("动画状态调试 [Tick: %d] | 当前动画: %s | 动画锁定: %s%n",
                zombie.tickCount,
                currentAnimation,
                animationLocked);
    }
    // 简化的骨骼位置获取方法 - 使用正确的 GeckoLib API

    public void setCurrentAnimation(String animation) {
        this.currentAnimation = animation;
    }// 在 ZombieAnimationController 类中替换这两个方法：

    public BoneTransform getBoneWorldTransform(String boneName) {
        try {
            // 确保这里调用了 ServerGeoModelLoader
            if (zombie.getServerAnimationSystem() != null) {
                return zombie.getServerAnimationSystem().calculateBoneTransform(boneName);
            }
            return new BoneTransform(zombie.position());
        } catch (Exception e) {
            System.err.println("获取骨骼变换失败: " + e.getMessage());
            return new BoneTransform(zombie.position());
        }
    }

    public Vec3 getBoneWorldPosition(String boneName) {
        BoneTransform transform = getBoneWorldTransform(boneName);
        return transform.position;
    }
}