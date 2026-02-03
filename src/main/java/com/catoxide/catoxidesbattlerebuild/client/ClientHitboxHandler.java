// [file name]: ClientHitboxHandler.java
package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.network.HitboxSyncPacket;
import com.catoxide.catoxidesbattlerebuild.server.hitboxsystem.BoneHitboxComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.GeckoLib;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端受击盒处理器
 */
public class ClientHitboxHandler {
    // 客户端存储的受击盒数据
    private static final Map<Integer, List<BoneHitboxComponent>> clientHitboxes = new ConcurrentHashMap<>();

    /**
     * 处理从服务端接收的受击盒同步数据
     */
    public static void handleHitboxSync(HitboxSyncPacket packet) {
        if (Minecraft.getInstance().level == null) return;

        clientHitboxes.clear();

        for (Map.Entry<Integer, List<HitboxSyncPacket.BoneHitboxData>> entry :
                packet.getEntityHitboxesMap().entrySet()) {

            int entityId = entry.getKey();
            Entity entity = Minecraft.getInstance().level.getEntity(entityId);

            if (entity == null) {
                // 实体不存在，跳过
                continue;
            }

            // 提取动画状态信息（从第一个受击盒数据中获取）
            AnimationStateInfo animState = null;
            if (!entry.getValue().isEmpty()) {
                HitboxSyncPacket.BoneHitboxData firstData = entry.getValue().get(0);
                animState = new AnimationStateInfo(
                        firstData.animationName,
                        firstData.animationTime,
                        firstData.animationSpeed,
                        firstData.looping
                );
            }

            // 更新客户端动画状态并触发解算
            if (animState != null && !animState.animationName.isEmpty()) {
                updateEntityAnimationState(entity, animState);
            }

            List<BoneHitboxComponent> hitboxList = new ArrayList<>();

            for (HitboxSyncPacket.BoneHitboxData data : entry.getValue()) {
                // 创建客户端的受击盒组件 - 使用客户端专用构造器（8个参数）
                BoneHitboxComponent hitbox = new BoneHitboxComponent(
                        entity.getUUID(),
                        data.boneName,
                        data.worldCenter,  // 服务器发来的已经是世界坐标
                        data.halfExtents,  // 半边长
                        data.worldOrientation,
                        data.damageMultiplier,
                        data.isCritical,
                        data.isArmored,
                        data.isActive
                );

                // 注意：客户端不需要updateWorldTransform，因为数据已经是世界坐标
                hitboxList.add(hitbox);
            }

            clientHitboxes.put(entityId, hitboxList);
        }

        // 更新客户端的HitboxSystem
        HitboxSystemClient.getInstance().updateHitboxes(clientHitboxes);
    }

    /**
     * 更新实体的动画状态并触发客户端解算
     */
    private static void updateEntityAnimationState(Entity entity, AnimationStateInfo animState) {
        try {
            // 获取实体模型位置
            ResourceLocation modelLocation = getModelLocation(entity);
            if (modelLocation == null) {
                GeckoLib.LOGGER.warn("Cannot get model location for entity {}", entity.getId());
                return;
            }

            // 更新客户端动画状态
            ClientEntityManager.getInstance().updateAnimationState(
                    entity.getUUID(),
                    modelLocation,
                    animState.animationName,
                    animState.animationTime,
                    animState.animationSpeed,
                    animState.looping
            );

            // 触发客户端动画解算
            ClientEntityManager.getInstance().resolveAnimation(
                    entity.getUUID(),
                    modelLocation,
                    Minecraft.getInstance().getPartialTick()
            );

            GeckoLib.LOGGER.debug("Updated animation state for entity {}: animation={}, time={}",
                    entity.getId(), animState.animationName, animState.animationTime);

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Failed to update animation state for entity {}: {}",
                    entity.getId(), e.getMessage(), e);
        }
    }

    /**
     * 获取实体的模型位置
     */
    private static ResourceLocation getModelLocation(Entity entity) {
        try {
            // 尝试从实体获取模型位置
            if (entity instanceof software.bernie.geckolib.core.animatable.GeoAnimatable geoAnimatable) {
                return geoAnimatable.getModelResource(geoAnimatable);
            }
        } catch (Exception e) {
            GeckoLib.LOGGER.warn("Failed to get model location: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取实体的客户端受击盒
     */
    public static List<BoneHitboxComponent> getEntityHitboxes(int entityId) {
        return clientHitboxes.getOrDefault(entityId, Collections.emptyList());
    }

    /**
     * 清理旧数据
     */
    public static void cleanup() {
        if (Minecraft.getInstance().level == null) {
            clientHitboxes.clear();
            return;
        }

        // 移除不存在的实体
        Iterator<Integer> iterator = clientHitboxes.keySet().iterator();
        while (iterator.hasNext()) {
            int entityId = iterator.next();
            if (Minecraft.getInstance().level.getEntity(entityId) == null) {
                iterator.remove();
            }
        }
    }

    /**
     * 动画状态信息
     */
    private static class AnimationStateInfo {
        final String animationName;
        final double animationTime;
        final double animationSpeed;
        final boolean looping;

        AnimationStateInfo(String animationName, double animationTime,
                          double animationSpeed, boolean looping) {
            this.animationName = animationName;
            this.animationTime = animationTime;
            this.animationSpeed = animationSpeed;
            this.looping = looping;
        }
    }
}