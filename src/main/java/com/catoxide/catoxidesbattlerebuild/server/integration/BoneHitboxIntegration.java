package com.catoxide.catoxidesbattlerebuild.server.integration;

import com.catoxide.catoxidesbattlerebuild.server.entities.*;
import com.catoxide.catoxidesbattlerebuild.server.models.BoneModelData;
import com.catoxide.catoxidesbattlerebuild.server.models.BoneModelDataExtractor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * 骨骼受击盒系统集成
 * 替代原始的HitboxSystemIntegration功能
 * 通过Forge事件系统集成到Minecraft游戏循环中
 */
@Mod.EventBusSubscriber
public class BoneHitboxIntegration {
    
    private static EntityBoneHitboxSystem hitboxSystem = EntityBoneHitboxSystem.getInstance();
    private static BoneCollectionFactory boneCollectionFactory = new BoneCollectionFactory();
    
    /**
     * 当实体加入世界时注册受击盒
     */
    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        
        // 检查实体是否需要骨骼受击盒（例如，具有自定义模型的生物）
        if (shouldRegisterHitboxForEntity(entity)) {
            long entityId = entity.getId();
            
            try {
                // 获取实体的模型位置（这需要根据实际的实体类型确定）
                ResourceLocation modelLocation = getModelLocationForEntity(entity);
                
                if (modelLocation != null) {
                    MinecraftServer server = event.getLevel().getServer();
                    ResourceManager resourceManager = server.getResourceManager();
                    
                    // 创建骨骼集合
                    Map<String, BoneCollection> boneCollections = 
                        boneCollectionFactory.createBoneCollections(entityId, modelLocation, resourceManager);
                    
                    // 注册到受击盒系统
                    hitboxSystem.registerEntity(entityId, boneCollections);
                    
                    System.out.println("Registered hitbox for entity " + entityId + " with model " + modelLocation);
                }
            } catch (Exception e) {
                System.err.println("Failed to register hitbox for entity " + entity.getId());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 当实体离开世界时注销受击盒
     */
    @SubscribeEvent
    public static void onEntityLeaveWorld(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        long entityId = entity.getId();
        
        // 检查实体是否有注册的受击盒
        if (hasRegisteredHitbox(entityId)) {
            hitboxSystem.unregisterEntity(entityId);
            System.out.println("Unregistered hitbox for entity " + entityId);
        }
    }
    
    /**
     * 服务器Tick事件处理
     * 更新所有活跃实体的骨骼变换
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            // 在服务器Tick结束时更新所有实体的骨骼变换
            hitboxSystem.updateAllEntityTransforms(1.0f); // 使用固定的partialTicks，实际应根据游戏时间计算
        }
    }
    
    /**
     * 世界Tick事件处理
     * 更精确的骨骼变换更新
     */
    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.world instanceof ServerLevel) {
            // 获取该世界中所有需要更新受击盒的实体
            for (Entity entity : ((ServerLevel) event.world).getAllEntities()) {
                long entityId = entity.getId();
                if (hasRegisteredHitbox(entityId)) {
                    // 使用实际的时间增量来更新变换
                    float partialTicks = getPartialTicks(event);
                    hitboxSystem.updateEntityTransforms(entityId, partialTicks);
                }
            }
        }
    }
    
    /**
     * 手动注册实体到受击系统
     */
    public static void manualRegisterEntity(Entity entity, ResourceLocation modelLocation) {
        long entityId = entity.getId();
        
        try {
            MinecraftServer server = entity.getServer();
            if (server != null) {
                ResourceManager resourceManager = server.getResourceManager();
                
                // 创建骨骼集合
                Map<String, BoneCollection> boneCollections = 
                    boneCollectionFactory.createBoneCollections(entityId, modelLocation, resourceManager);
                
                // 注册到受击盒系统
                hitboxSystem.registerEntity(entityId, boneCollections);
                
                System.out.println("Manually registered hitbox for entity " + entityId + " with model " + modelLocation);
            }
        } catch (Exception e) {
            System.err.println("Failed to manually register hitbox for entity " + entity.getId());
            e.printStackTrace();
        }
    }
    
    /**
     * 检查实体是否需要受击盒
     */
    private static boolean shouldRegisterHitboxForEntity(Entity entity) {
        // 根据实体类型决定是否需要骨骼受击盒
        // 通常是对具有复杂动画的生物或自定义模型实体
        return entity instanceof LivingEntity && hasCustomModel(entity);
    }
    
    /**
     * 检查实体是否有自定义模型
     */
    private static boolean hasCustomModel(Entity entity) {
        // 这是一个简化的检查，实际实现需要根据你的实体系统设计
        // 可能基于实体的类型、NBT数据或其他标识
        return true; // 假设所有实体都有自定义模型，实际应根据具体情况判断
    }
    
    /**
     * 为实体获取模型位置
     */
    private static ResourceLocation getModelLocationForEntity(Entity entity) {
        // 根据实体类型确定其模型位置
        // 这需要与你的实体模型系统集成
        String entityType = entity.getType().getRegistryName().getPath();
        
        // 示例：为不同类型的实体分配不同的模型
        if (entityType.contains("zombie")) {
            return ResourceLocation.fromNamespaceAndPath("catoxide", "zombie_model");
        } else if (entityType.contains("skeleton")) {
            return ResourceLocation.fromNamespaceAndPath("catoxide", "skeleton_model");
        } else if (entityType.contains("player")) {
            return ResourceLocation.fromNamespaceAndPath("catoxide", "player_model");
        } else {
            // 默认模型
            return ResourceLocation.fromNamespaceAndPath("catoxide", "generic_model");
        }
    }
    
    /**
     * 检查实体是否已注册受击盒
     */
    private static boolean hasRegisteredHitbox(long entityId) {
        return hitboxSystem.getEntityBoundingBox(entityId).isPresent();
    }
    
    /**
     * 获取部分刻度时间
     */
    private static float getPartialTicks(TickEvent.WorldTickEvent event) {
        // 实际实现应该根据游戏时间和渲染需求计算
        // 这里返回一个模拟值
        return 1.0f;
    }
    
    /**
     * 检测射线与实体的碰撞
     */
    public static java.util.Optional<EntityBoneHitboxSystem.EntityHitResult> raycastEntity(
            long entityId, Vec3 start, Vec3 direction, double maxDistance) {
        return hitboxSystem.raycastEntity(entityId, start, direction, maxDistance);
    }
    
    /**
     * 处理实体击中
     */
    public static EntityBoneHitboxSystem.EntityHitResult processEntityHit(
            long entityId, Vec3 hitPoint, float incomingDamage) {
        return hitboxSystem.processEntityHit(entityId, hitPoint, incomingDamage);
    }
}