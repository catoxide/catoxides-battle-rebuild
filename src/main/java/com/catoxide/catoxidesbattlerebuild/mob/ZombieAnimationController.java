package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

public class ZombieAnimationController {
    private final ModularZombie zombie;

    private String currentAnimation = "";
    private boolean animationLocked = false;
    private int animationLockTime = 0;

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
        controllers.add(new AnimationController<>(zombie, "MainController", 5, this::mainControllerHandler));
    }

    private PlayState mainControllerHandler(AnimationState<ModularZombie> state) {
        if (zombie.isDeadOrDying()) {
            return PlayState.STOP;
        }

        if (zombie.isHit()) {
            return handleHitAnimation(state);
        }

        if (zombie.swinging) {
            return handleAttackAnimation(state);
        }

        if (zombie.isWindingUp() || zombie.isInPostAttackCooldown()) {
            return handleCombatAnimation(state);
        }

        if (zombie.isAlerting() && !zombie.isAlertCompleted()) {
            return handleAlertAnimation(state);
        }

        if (zombie.hasTarget() && zombie.isAlertCompleted()) {
            return handleTargetMovementAnimation(state);
        }

        return handleIdleMovementAnimation(state);
    }

    private PlayState handleHitAnimation(AnimationState<ModularZombie> state) {
        animationLocked = true;
        animationLockTime = 20;

        RawAnimation hitAnim = zombie.getRandom().nextBoolean() ? HIT_FRONT_ANIMATION : HIT_BACK_ANIMATION;

        if (!"hit".equals(currentAnimation)) {
            currentAnimation = "hit";
            state.getController().setAnimation(hitAnim);
            return PlayState.CONTINUE;
        }

        return PlayState.CONTINUE;
    }

    private PlayState handleAttackAnimation(AnimationState<ModularZombie> state) {
        animationLocked = true;
        animationLockTime = 20;

        if (!"attack".equals(currentAnimation)) {
            currentAnimation = "attack";
            state.getController().setAnimation(ATTACK_ANIMATION);
            return PlayState.CONTINUE;
        }

        return PlayState.CONTINUE;
    }

    private PlayState handleCombatAnimation(AnimationState<ModularZombie> state) {
        if (!"aggressive".equals(currentAnimation)) {
            currentAnimation = "aggressive";
            state.getController().setAnimation(AGGRESSIVE_ANIMATION);
            return PlayState.CONTINUE;
        }

        return PlayState.CONTINUE;
    }

    private PlayState handleAlertAnimation(AnimationState<ModularZombie> state) {
        if (!"alert".equals(currentAnimation)) {
            currentAnimation = "alert";
            state.getController().setAnimation(ALERT_ANIMATION);
            return PlayState.CONTINUE;
        }

        return PlayState.CONTINUE;
    }

    private PlayState handleTargetMovementAnimation(AnimationState<ModularZombie> state) {
        if (shouldMoveToTarget()) {
            if (!"running".equals(currentAnimation)) {
                currentAnimation = "running";
                state.getController().setAnimation(RUNNING_ANIMATION);
                return PlayState.CONTINUE;
            }
        } else {
            if (!"aggressive".equals(currentAnimation)) {
                currentAnimation = "aggressive";
                state.getController().setAnimation(AGGRESSIVE_ANIMATION);
                return PlayState.CONTINUE;
            }
        }

        return PlayState.CONTINUE;
    }

    private PlayState handleIdleMovementAnimation(AnimationState<ModularZombie> state) {
        if (zombie.getDeltaMovement().horizontalDistanceSqr() > 0.001) {
            if (!"walking".equals(currentAnimation)) {
                currentAnimation = "walking";
                state.getController().setAnimation(WALKING_ANIMATION);
                return PlayState.CONTINUE;
            }
        } else {
            if (!"idle".equals(currentAnimation)) {
                currentAnimation = "idle";
                state.getController().setAnimation(IDLE_ANIMATION);
                return PlayState.CONTINUE;
            }
        }

        return PlayState.CONTINUE;
    }

    private boolean shouldMoveToTarget() {
        if (!zombie.hasTarget()) {
            return false;
        }

        LivingEntity target = zombie.getTarget();
        if (target == null) {
            return false;
        }

        double distance = zombie.distanceTo(target);
        double attackRange = zombie.getAIManager().getCurrentAttackRange();

        if (distance <= attackRange) {
            return false;
        }

        PathNavigation navigation = zombie.getNavigation();
        boolean hasPath = navigation.getPath() != null && !navigation.isDone();

        return hasPath || distance > attackRange + 1.0;
    }

    public void tick() {
        if (animationLocked) {
            animationLockTime--;
            if (animationLockTime <= 0) {
                animationLocked = false;
            }
        }
    }

    public void triggerHit() {
        animationLocked = true;
        animationLockTime = 20;
    }

    public void reset() {
        currentAnimation = "idle";
        animationLocked = false;
    }

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

    public void setCurrentAnimation(String animation) {
        this.currentAnimation = animation;
    }
}