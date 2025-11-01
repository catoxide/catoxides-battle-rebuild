package com.catoxide.catoxidesbattlerebuild.mob.server;

import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.BoneTransform;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.network.NetworkHandler;
import net.minecraft.world.phys.Vec3;

import java.util.*;

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

        if (tickCount % 1 == 0) {
            updateBoneTransforms();
        }

        // 新增：发送骨骼调试数据
        sendBoneDebugData();

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

    private void sendBoneDebugData() {
        if (zombie.tickCount % 10 == 0) { // 每10tick发送一次
            List<BoneDebugPacket.BoneData> boneDataList = new ArrayList<>();

            for (String boneName : getCollisionBones()) {
                BoneTransform transform = calculateBoneTransform(boneName);
                // 简化处理：父骨骼位置使用实体位置
                Vec3 parentPos = zombie.position();

                BoneDebugPacket.BoneData boneData = new BoneDebugPacket.BoneData(
                        boneName,
                        transform.position,
                        transform.rotation,
                        parentPos
                );
                boneDataList.add(boneData);
            }

            BoneDebugPacket packet = new BoneDebugPacket(zombie.getId(), boneDataList);
            // 使用 NetworkHandler 发送
            NetworkHandler.sendToAllTracking(packet, zombie);

            // 调试输出
            if (zombie.tickCount % 100 == 0) {
                System.out.println("发送骨骼调试数据 - 实体ID: " + zombie.getId() + ", 骨骼数量: " + boneDataList.size());
            }
        }
    }
}