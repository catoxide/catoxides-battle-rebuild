// [file name]: ClientHitboxHandler.java
package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.network.HitboxSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
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
        long startTime = System.currentTimeMillis();
        GeckoLib.LOGGER.info("[HitboxSync] Starting to handle hitbox sync packet...");
        
        if (Minecraft.getInstance().level == null) {
            GeckoLib.LOGGER.warn("[HitboxSync] Level is null, skipping packet");
            return;
        }

        clientHitboxes.clear();
        
        Map<Integer, List<HitboxSyncPacket.BoneHitboxData>> entityHitboxesMap = packet.getEntityHitboxesMap();
        GeckoLib.LOGGER.info("[HitboxSync] Received hitbox data for {} entities", entityHitboxesMap.size());

        for (Map.Entry<Integer, List<HitboxSyncPacket.BoneHitboxData>> entry :
                entityHitboxesMap.entrySet()) {

            int entityId = entry.getKey();
            Entity entity = Minecraft.getInstance().level.getEntity(entityId);

            if (entity == null) {
                GeckoLib.LOGGER.debug("[HitboxSync] Entity {} not found in level", entityId);
                continue;
            }

            GeckoLib.LOGGER.debug("[HitboxSync] Processing entity {} ({}) with {} hitboxes",
                    entityId, entity.getName().getString(), entry.getValue().size());

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
                
                GeckoLib.LOGGER.debug("[HitboxSync] Animation state - Name: {}, Time: {}, Speed: {}, Looping: {}",
                        animState.animationName, animState.animationTime, animState.animationSpeed, animState.looping);
            }

            // 更新客户端动画状态并触发解算
            if (animState != null && !animState.animationName.isEmpty()) {
                GeckoLib.LOGGER.debug("[HitboxSync] Triggering animation state update for entity {}", entityId);
                updateEntityAnimationState(entity, animState);
            } else {
                GeckoLib.LOGGER.debug("[HitboxSync] No valid animation state for entity {}", entityId);
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

                GeckoLib.LOGGER.trace("[HitboxSync] Created hitbox for bone '{}' - Active: {}",
                        data.boneName, data.isActive);

                // 注意：客户端不需要updateWorldTransform，因为数据已经是世界坐标
                hitboxList.add(hitbox);
            }

            clientHitboxes.put(entityId, hitboxList);
            GeckoLib.LOGGER.debug("[HitboxSync] Stored {} hitboxes for entity {}", hitboxList.size(), entityId);
        }

        // 更新客户端的HitboxSystem
        GeckoLib.LOGGER.debug("[HitboxSync] Updating HitboxSystemClient with {} entities", clientHitboxes.size());
        HitboxSystemClient.getInstance().updateHitboxes(clientHitboxes);
        
        long endTime = System.currentTimeMillis();
        GeckoLib.LOGGER.info("[HitboxSync] Hitbox sync completed in {} ms", endTime - startTime);
    }

    /**
     * 更新实体的动画状态并触发客户端解算
     */
    private static void updateEntityAnimationState(Entity entity, AnimationStateInfo animState) {
        long startTime = System.currentTimeMillis();
        GeckoLib.LOGGER.info("[AnimationUpdate] Starting animation update for entity {} ({})",
                entity.getId(), entity.getName().getString());
        
        try {
            // 获取实体模型位置
            ResourceLocation modelLocation = getModelLocation(entity);
            if (modelLocation == null) {
                GeckoLib.LOGGER.warn("[AnimationUpdate] Cannot get model location for entity {}", entity.getId());
                return;
            }
            
            GeckoLib.LOGGER.debug("[AnimationUpdate] Model location: {}", modelLocation);

            // 更新客户端动画状态
            ClientEntityManager.getInstance().updateAnimationState(
                    entity.getUUID(),
                    animState.animationName,
                    animState.animationTime,
                    animState.animationSpeed,
                    animState.looping
            );

            // 触发客户端动画解算
            float partialTick = Minecraft.getInstance().getPartialTick();
            Map<String, org.joml.Matrix4f> boneMatrices = ClientEntityManager.getInstance().resolveAnimation(
                    entity.getUUID(),
                    partialTick
            );
            
            GeckoLib.LOGGER.info("[AnimationUpdate] Animation resolution completed. Bone matrices count: {}",
                    boneMatrices != null ? boneMatrices.size() : 0);

            long endTime = System.currentTimeMillis();
            GeckoLib.LOGGER.info("[AnimationUpdate] Animation update completed in {} ms", endTime - startTime);

        } catch (Exception e) {
            GeckoLib.LOGGER.error("[AnimationUpdate] Failed to update animation state for entity {}: {}",
                    entity.getId(), e.getMessage(), e);
        }
    }

    /**
     * 获取实体的模型位置
     */
    private static ResourceLocation getModelLocation(Entity entity) {
        try {
            // 参考服务端实现，使用模型管理器获取模型位置
            // 1. 获取实体的核心标识（去除符号）
            String entityCore = getEntityCoreIdentifier(entity);

            if (entityCore.isEmpty()) {
                return null;
            }

            // 2. 获取所有已加载的模型
            Collection<ResourceLocation> loadedModels = 
                    ClientEntityManager.getInstance().getAllModelLocations();

            if (loadedModels == null || loadedModels.isEmpty()) {
                return null;
            }

            // 3. 遍历所有模型，查找完全匹配的
            for (ResourceLocation modelLocation : loadedModels) {
                String modelCore = getModelCoreIdentifier(modelLocation);

                if (entityCore.equals(modelCore)) {
                    GeckoLib.LOGGER.debug("Exact match found: {} -> {}", entityCore, modelLocation);
                    return modelLocation;
                }
            }

            // 4. 没有找到匹配的模型
            GeckoLib.LOGGER.warn("No exact model match for entity: {} (core identifier: {})",
                    entity.getDisplayName().getString(), entityCore);
        } catch (Exception e) {
            GeckoLib.LOGGER.warn("Failed to get model location: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取实体的核心标识符（去除所有符号）
     */
    private static String getEntityCoreIdentifier(Entity entity) {
        // 获取实体类型的注册表名称
        ResourceLocation entityTypeKey = EntityType.getKey(entity.getType());
        if (entityTypeKey == null) {
            return "";
        }

        // 获取实体类型名称（如 "zombie"）
        String entityTypeName = entityTypeKey.getPath();

        // 规范化标识符
        return normalizeIdentifier(entityTypeName);
    }

    /**
     * 获取模型的核心标识符（去除所有符号）
     */
    private static String getModelCoreIdentifier(ResourceLocation modelLocation) {
        // 获取模型路径（如 "geo/elder_guardian.geo.json"）
        String path = modelLocation.getPath();

        // 移除 "geo/" 前缀
        if (path.startsWith("geo/")) {
            path = path.substring(4);
        }

        // 移除 ".geo.json" 后缀
        if (path.endsWith(".geo.json")) {
            path = path.substring(0, path.length() - 9);
        }

        // 移除子目录（如果有）
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash != -1) {
            path = path.substring(lastSlash + 1);
        }

        // 规范化标识符
        return normalizeIdentifier(path);
    }

    /**
     * 规范化标识符（去除所有符号，转为小写）
     */
    private static String normalizeIdentifier(String identifier) {
        // 移除所有非字母数字字符
        String normalized = identifier.replaceAll("\\W+", "");
        // 转为小写
        return normalized.toLowerCase();
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