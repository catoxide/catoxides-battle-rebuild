package com.catoxide.catoxidesbattlerebuild.mob.server;

import com.catoxide.catoxidesbattlerebuild.mob.BoneTransform;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServerAnimationSystem {
    private final ModularZombie zombie;
    private final Map<String, BoneTransform> boneCache = new HashMap<>();
    private int tickCount = 0;
    private final ServerGeoModelLoader geoModelLoader; // 添加实例

    public ServerAnimationSystem(ModularZombie zombie) {
        this.zombie = zombie;
        // 创建 ServerGeoModelLoader 实例
        this.geoModelLoader = new ServerGeoModelLoader(zombie, zombie.getModel());
    }

    public void serverTick() {
        if (zombie.level().isClientSide) return;

        updateAnimationState();

        if (tickCount % 3 == 0) {
            updateBoneTransforms();
        }
        tickCount++;
    }

    private void updateAnimationState() {
        String animationState = decideAnimation();
        zombie.triggerAnim("controller", animationState);
    }

    // 添加缺失的方法
    private String decideAnimation() {
        // 简单的动画决策逻辑
        if (zombie.isHit()) {
            return "hit";
        } else if (zombie.swinging) {
            return "attack";
        } else if (zombie.hasTarget()) {
            return zombie.getDeltaMovement().horizontalDistanceSqr() > 0.001 ? "running" : "aggressive";
        } else {
            return zombie.getDeltaMovement().horizontalDistanceSqr() > 0.001 ? "walking" : "idle";
        }
    }

    private void updateBoneTransforms() {
        boneCache.clear();

        for (String boneName : getCollisionBones()) {
            // 使用实例方法而不是静态方法
            BoneTransform transform = geoModelLoader.getBoneWorldTransform(boneName);
            boneCache.put(boneName, transform);
        }
    }

    private List<String> getCollisionBones() {
        return Arrays.asList("head", "body", "left_arm", "right_arm", "left_leg", "right_leg");
    }

    public BoneTransform getBoneTransform(String boneName) {
        return boneCache.getOrDefault(boneName, BoneTransform.IDENTITY);
    }

    // 修改静态方法为实例方法
    public BoneTransform calculateBoneTransform(String boneName) {
        return geoModelLoader.getBoneWorldTransform(boneName);
    }
}