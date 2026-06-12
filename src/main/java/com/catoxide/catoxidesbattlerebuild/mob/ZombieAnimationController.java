package com.catoxide.catoxidesbattlerebuild.mob;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

public class ZombieAnimationController {
    private final ModularZombie zombie;
    private AnimationController<ModularZombie> controller;

    private RawAnimation currentAnimation = IDLE_ANIMATION;
    private String currentAnimationName = "animation.zombie.still";

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
        controller = new AnimationController<>(zombie, "MainController", 5, this::mainControllerHandler);
        controller.triggerableAnim("hit_front", HIT_FRONT_ANIMATION);
        controller.triggerableAnim("hit_back", HIT_BACK_ANIMATION);
        controller.receiveTriggeredAnimations();
        controllers.add(controller);
    }

    private PlayState mainControllerHandler(AnimationState<ModularZombie> state) {
        if (zombie.isDeadOrDying()) {
            return PlayState.STOP;
        }

        if (controller != null && controller.isPlayingTriggeredAnimation()) {
            LogManager.animationDebug(String.valueOf(zombie.getId()), "triggered_anim_playing", "", 100);
            return PlayState.CONTINUE;
        }

        if (zombie.isHit()) {
            LogManager.animationDebug(String.valueOf(zombie.getId()), "hit_pending", "isPlaying=false but isHit=true", 100);
            return PlayState.CONTINUE;
        }

        RawAnimation targetAnimation = determineTargetAnimation();

        if (targetAnimation != currentAnimation) {
            String newAnimName = getAnimationName(targetAnimation);
            if (isHitTransition(currentAnimationName, newAnimName) || isReturnToStill(newAnimName)) {
                String transition = String.format("TRANSITION: %s -> %s", currentAnimationName, newAnimName);
                LogManager.animationDebug(String.valueOf(zombie.getId()), newAnimName, transition);
            }
            state.getController().setAnimation(targetAnimation);
            currentAnimation = targetAnimation;
            currentAnimationName = newAnimName;
        }

        return PlayState.CONTINUE;
    }

    private String getAnimationName(RawAnimation anim) {
        if (anim == IDLE_ANIMATION) return "animation.zombie.still";
        if (anim == WALKING_ANIMATION) return "animation.zombie.walking";
        if (anim == AGGRESSIVE_ANIMATION) return "animation.zombie.aggressive";
        if (anim == RUNNING_ANIMATION) return "animation.zombie.running";
        if (anim == ALERT_ANIMATION) return "animation.zombie.alert";
        if (anim == ATTACK_ANIMATION) return "animation.zombie.attack";
        if (anim == HIT_FRONT_ANIMATION) return "animation.zombie.hit_front";
        if (anim == HIT_BACK_ANIMATION) return "animation.zombie.hit_back";
        return "unknown";
    }

    private boolean isHitTransition(String oldAnim, String newAnim) {
        return newAnim.contains("hit_front") || newAnim.contains("hit_back");
    }

    private boolean isReturnToStill(String newAnim) {
        return newAnim.contains("still") || newAnim.contains("idle");
    }

    private RawAnimation determineTargetAnimation() {
        if (zombie.swinging) {
            return ATTACK_ANIMATION;
        }

        if (zombie.isWindingUp() || zombie.isInPostAttackCooldown()) {
            return AGGRESSIVE_ANIMATION;
        }

        if (zombie.isAlerting() && !zombie.isAlertCompleted()) {
            return ALERT_ANIMATION;
        }

        if (zombie.hasTarget() && zombie.isAlertCompleted()) {
            return determineTargetMovementAnimation();
        }

        return determineIdleAnimation();
    }

    private RawAnimation determineTargetMovementAnimation() {
        LivingEntity target = zombie.getTarget();
        if (target == null) {
            return AGGRESSIVE_ANIMATION;
        }

        if (zombie.isSprinting()) {
            return RUNNING_ANIMATION;
        }

        double distance = zombie.distanceTo(target);
        double attackRange = zombie.getAIManager().getCurrentAttackRange();

        if (distance <= attackRange) {
            return AGGRESSIVE_ANIMATION;
        }

        if (!zombie.getNavigation().isInProgress() && !isMoving()) {
            return AGGRESSIVE_ANIMATION;
        }

        return RUNNING_ANIMATION;
    }

    private RawAnimation determineIdleAnimation() {
        if (zombie.getDeltaMovement().horizontalDistanceSqr() > 0.001) {
            return WALKING_ANIMATION;
        }
        return IDLE_ANIMATION;
    }

    private boolean isMoving() {
        return zombie.getDeltaMovement().horizontalDistanceSqr() > 0.001;
    }

    public void tick() {
    }

    public void triggerHit() {
    }

    public void reset() {
        currentAnimation = IDLE_ANIMATION;
        currentAnimationName = "animation.zombie.still";
    }

    public String getAnimationStateInfo() {
        if (controller != null && controller.isPlayingTriggeredAnimation()) return "受击动画播放中";
        if (zombie.swinging) return "攻击状态";
        if (zombie.isWindingUp()) return "攻击前摇";
        if (zombie.isInPostAttackCooldown()) return "攻击后冷却";
        if (zombie.isAlerting() && !zombie.isAlertCompleted()) return "警戒中";
        if (zombie.hasTarget() && zombie.isAlertCompleted()) {
            return determineTargetMovementAnimation() == RUNNING_ANIMATION ? "追击目标" : "警戒待机";
        }
        if (zombie.getDeltaMovement().horizontalDistanceSqr() > 0.001) return "随机移动";
        return "空闲状态";
    }

    public RawAnimation getCurrentAnimation() {
        return currentAnimation;
    }
}