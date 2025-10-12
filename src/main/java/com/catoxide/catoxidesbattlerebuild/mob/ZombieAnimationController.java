package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;

public class ZombieAnimationController {
    private final ModularZombie zombie;

    // 动画状态跟踪
    private String currentAnimation = "";
    private boolean animationLocked = false;
    private int animationLockTime = 0;
    private boolean attackTriggered = false;

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
        // 主行为控制器 - 处理状态逻辑
        controllers.add(new AnimationController<>(zombie, "Behavior", 5, this::behaviorPredicate).transitionLength(3));
        // 攻击控制器 - 独立处理攻击
        controllers.add(new AnimationController<>(zombie, "Attack", 2, this::attackPredicate).transitionLength(3));
        // 受击控制器 - 独立处理受击
        controllers.add(new AnimationController<>(zombie, "Hit", 2, this::hitPredicate).transitionLength(3));
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

        // 检测攻击状态
        if (zombie.swinging && !attackTriggered) {
            attackTriggered = true;
            System.out.println("检测到攻击动作");
        } else if (!zombie.swinging && attackTriggered) {
            attackTriggered = false;
        }
    }

    // 触发攻击动画
    public void triggerAttack() {
        attackTriggered = true;
    }

    // 触发受击动画
    public void triggerHit() {
        animationLocked = true;
        animationLockTime = 25;
        attackTriggered = false;
    }

    // 重置动画状态
    public void reset() {
        currentAnimation = "idle";
        animationLocked = false;
        attackTriggered = false;
    }

    // 获取当前动画状态信息
    public String getAnimationStateInfo() {
        if (zombie.isHit()) return "受击动画";
        if (attackTriggered) return "攻击动画";
        if (zombie.isWindingUp()) return "攻击前摇动画";
        if (zombie.isAlerting() && !zombie.isAlertCompleted()) return "警戒动画";
        if (zombie.hasTarget() && zombie.isAlertCompleted()) {
            if (isPurposefulMoving()) return "奔跑动画";
            else return "警戒待机动画";
        }
        if (zombie.getDeltaMovement().horizontalDistanceSqr() > 0.001) return "行走动画";
        return "空闲动画";
    }

    // 行为动画谓词
    private PlayState behaviorPredicate(AnimationState<ModularZombie> state) {
        if (zombie.isDeadOrDying()) {
            return PlayState.STOP;
        }

        // 受击状态优先级最高
        if (zombie.isHit()) {
            return PlayState.STOP;
        }

        // 攻击前摇状态
        if (zombie.isWindingUp()) {
            if (!"aggressive".equals(currentAnimation)) {
                currentAnimation = "aggressive";
                System.out.println("播放攻击前摇动画");
            }
            return state.setAndContinue(AGGRESSIVE_ANIMATION);
        }

        // 攻击动画期间停止行为动画
        if (attackTriggered) {
            return PlayState.STOP;
        }

        String desiredAnimation = getDesiredBehaviorAnimation();

        if (desiredAnimation == null || desiredAnimation.isEmpty()) {
            desiredAnimation = "idle";
        }

        // 只有当动画改变时才设置新动画
        if (!desiredAnimation.equals(currentAnimation)) {
            currentAnimation = desiredAnimation;
            System.out.println("切换动画到: " + desiredAnimation);

            switch (desiredAnimation) {
                case "alert":
                    return state.setAndContinue(ALERT_ANIMATION);
                case "aggressive":
                    return state.setAndContinue(AGGRESSIVE_ANIMATION);
                case "running":
                    return state.setAndContinue(RUNNING_ANIMATION);
                case "walking":
                    return state.setAndContinue(WALKING_ANIMATION);
                case "idle":
                default:
                    return state.setAndContinue(IDLE_ANIMATION);
            }
        }

        return PlayState.CONTINUE;
    }

    // 攻击动画谓词
    private PlayState attackPredicate(AnimationState<ModularZombie> state) {
        if (zombie.isDeadOrDying()) {
            return PlayState.STOP;
        }

        // 受击状态优先于攻击
        if (zombie.isHit()) {
            return PlayState.STOP;
        }

        if (attackTriggered && !animationLocked) {
            animationLocked = true;
            animationLockTime = 20;
            System.out.println("触发攻击动画");
            return state.setAndContinue(ATTACK_ANIMATION);
        }
        return PlayState.STOP;
    }

    // 受击动画谓词
    private PlayState hitPredicate(AnimationState<ModularZombie> state) {
        if (zombie.isDeadOrDying()) {
            return PlayState.STOP;
        }

        if (zombie.isHit()) {
            // 确保受击期间不播放其他动画
            animationLocked = true;
            animationLockTime = 20;

            RawAnimation hitAnim = zombie.getRandom().nextBoolean() ? HIT_FRONT_ANIMATION : HIT_BACK_ANIMATION;
            System.out.println("播放受击动画: " + (hitAnim == HIT_FRONT_ANIMATION ? "正面" : "背面"));
            return state.setAndContinue(hitAnim);
        }
        return PlayState.STOP;
    }

    // 获取期望的行为动画
    private String getDesiredBehaviorAnimation() {
        // 受击状态由受击控制器处理
        if (zombie.isHit()) {
            return "hit";
        }

        // 攻击前摇状态
        if (zombie.isWindingUp()) {
            return "aggressive"; // 使用警戒姿势作为前摇动画
        }

        // 警戒动画
        if (zombie.isAlerting() && !zombie.isAlertCompleted()) {
            return "alert";
        }

        // 有目标状态
        if (zombie.hasTarget() && zombie.isAlertCompleted()) {
            return "running";
        }

        // 无目标状态
        if (zombie.getDeltaMovement().horizontalDistanceSqr() > 0.001) {
            return "walking";
        } else {
            return "idle";
        }
    }

    // 目的性移动检测
    private boolean isPurposefulMoving() {
        if (!zombie.hasTarget()) {
            return false;
        }

        LivingEntity target = zombie.getTarget();
        if (target == null) {
            return false;
        }

        PathNavigation navigation = zombie.getNavigation();

        // 更详细的移动检测
        boolean isNavigating = !navigation.isDone();
        boolean hasMovement = zombie.getDeltaMovement().horizontalDistanceSqr() > 0.001;
        boolean isPathFinding = navigation.getPath() != null;

        // 检查与目标的距离变化
        boolean isClosingDistance = false;
        double currentDistance = zombie.distanceTo(target);
        if (zombie.getLastDistanceToTarget() > 0) {
            isClosingDistance = currentDistance < zombie.getLastDistanceToTarget() - 0.5; // 增加阈值避免微小波动
        }
        zombie.setLastDistanceToTarget(currentDistance);

        // 如果是攻击前摇，不算目的性移动
        if (zombie.isWindingUp()) {
            return false;
        }

        boolean result = isNavigating || hasMovement || isPathFinding || isClosingDistance;

        // 调试输出
        if (zombie.tickCount % 80 == 0) {
            System.out.printf("目的性移动检查 | 导航中: %s | 有移动: %s | 有路径: %s | 接近中: %s | 结果: %s%n",
                    isNavigating, hasMovement, isPathFinding, isClosingDistance, result);
        }

        return result;
    }

    // 调试方法
    public void debugAnimationState() {
        if (zombie.level().isClientSide) {
            return;
        }

        String desiredAnimation = getDesiredBehaviorAnimation();
        String currentAnimDisplay = (currentAnimation == null || currentAnimation.isEmpty()) ? "空" : currentAnimation;

        System.out.printf("动画状态调试 [Tick: %d] | 期望动画: %s | 当前动画: %s | 攻击触发: %s | 动画锁定: %s%n",
                zombie.tickCount,
                desiredAnimation,
                currentAnimDisplay,
                attackTriggered,
                animationLocked);
    }
}