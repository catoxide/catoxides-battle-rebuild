package com.catoxide.catoxidesbattlerebuild.server.integration;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.*;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.config.*;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.factory.*;
import com.catoxide.catoxidesbattlerebuild.server.models.BoneModelData;
import com.catoxide.catoxidesbattlerebuild.server.models.ModelDataManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

/**
 * 骨骼BodyUnit系统集成
 * 替代原始的HitboxSystemIntegration功能
 * 通过Forge事件系统集成到Minecraft游戏循环中
 */
@Mod.EventBusSubscriber
public class BoneBodyUnitIntegration {
    
    private static EntityBoneBodyUnitSystem bodyUnitSystem = EntityBoneBodyUnitSystem.getInstance();
    private static BodyPartFactory bodyPartFactory = BodyPartFactory.getInstance();
    
    // 默认部位名称
    private static final String DEFAULT_PART_NAME = "main";
    
    /**
     * 当实体加入世界时注册BodyUnit
     */
    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        
        // 检查实体是否需要骨骼BodyUnit（例如，具有自定义模型的生物）
        if (shouldRegisterBodyUnitForEntity(entity)) {
            long entityId = entity.getId();
            
            try {
                // 获取实体的模型位置（这需要根据实际的实体类型确定）
                ResourceLocation modelLocation = getModelLocationForEntity(entity);
                
                if (modelLocation != null) {
                    MinecraftServer server = event.getLevel().getServer();
                    ResourceManager resourceManager = server.getResourceManager();
                    
                    // 创建BodyPart并注册到系统
                    createAndRegisterBodyParts(entityId, modelLocation, resourceManager);
                    
                    System.out.println("Registered body parts for entity " + entityId + " with model " + modelLocation);
                }
            } catch (Exception e) {
                System.err.println("Failed to register body parts for entity " + entity.getId());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 当实体离开世界时注销BodyUnit
     */
    @SubscribeEvent
    public static void onEntityLeaveWorld(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        long entityId = entity.getId();
        
        // 检查实体是否有注册的BodyPart
        if (hasRegisteredBodyPart(entityId)) {
            bodyUnitSystem.unregisterEntity(entityId);
            System.out.println("Unregistered body parts for entity " + entityId);
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
            // 注意：骨骼变换更新由EntityCollectionFactory和ServerGeoModelManager处理
            // 这里不需要额外调用，因为GeckoLib会自动处理动画更新
        }
    }
    
    /**
     * 客户端Tick事件处理（用于客户端同步）
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            // 客户端Tick处理（如果需要）
        }
    }
    
    /**
     * 创建并注册BodyPart
     * 新架构：从模型数据创建BodyPart和BodyUnit
     */
    private static void createAndRegisterBodyParts(long entityId, ResourceLocation modelLocation, ResourceManager resourceManager) {
        // 1. 使用UnifiedModelDataManager获取BoneModelData
        ModelDataManager unifiedManager = ModelDataManager.getInstance();
        BoneModelData boneModelData = unifiedManager.getBoneModelData(modelLocation);
        
        if (boneModelData == null) {
            throw new RuntimeException("Failed to load BoneModelData for: " + modelLocation);
        }
        
        // 2. 创建默认BodyPart配置
        // TODO: 从配置文件读取，暂时使用默认配置
        IBodyPartConfig partConfig = createDefaultBodyPartConfig();
        
        // 3. 为每个骨骼创建BodyUnit配置
        Map<String, IBodyUnitConfig> boneToUnitConfigMapping = new HashMap<>();
        for (String boneName : boneModelData.boneStaticDataMap().keySet()) {
            // TODO: 从配置文件读取，暂时使用默认配置
            IBodyUnitConfig unitConfig = createDefaultBodyUnitConfig(boneName);
            boneToUnitConfigMapping.put(boneName, unitConfig);
        }
        
        // 4. 创建BodyPart（包含所有骨骼的BodyUnit）
        IBodyPart bodyPart = bodyPartFactory.createBodyPart(
            entityId, 
            DEFAULT_PART_NAME, 
            partConfig, 
            boneToUnitConfigMapping
        );
        
        // 5. 注册到系统
        List<IBodyPart> bodyParts = Collections.singletonList(bodyPart);
        bodyUnitSystem.registerEntityWithBodyParts(entityId, bodyParts);
    }
    
    /**
     * 创建默认BodyPart配置
     * TODO: 从配置文件读取
     */
    private static IBodyPartConfig createDefaultBodyPartConfig() {
        // 使用工厂创建标准配置
        return bodyPartFactory.createStandardConfig(
            "default_part",
            20.0f,  // 基础血量
            1.0f,   // 伤害倍率
            0.0f    // 护甲值（无护甲）
        );
    }
    
    /**
     * 创建默认BodyUnit配置
     * TODO: 从配置文件读取
     */
    private static IBodyUnitConfig createDefaultBodyUnitConfig(String boneName) {
        // 创建默认配置
        return new IBodyUnitConfig() {
            @Override
            public String getConfigName() {
                return "default_unit_" + boneName;
            }
            
            @Override
            public String getBodyUnitName() {
                return boneName;
            }
            
            @Override
            public String getBoneName() {
                return boneName;
            }
            
            @Override
            public float getMaxHealth() {
                return 10.0f; // 默认血量
            }
            
            @Override
            public float getArmorValue() {
                return 0.0f; // 无护甲
            }
            
            @Override
            public boolean isCritical() {
                return false;
            }
            
            @Override
            public String getCollisionTag() {
                return "default";
            }
            
            @Override
            public float getTransmissionCoefficient() {
                return 1.0f; // 完全传导
            }
            
            @Override
            public List<String> getSpecialEffects() {
                return Collections.emptyList();
            }
            
            @Override
            public ResourceLocation getHitSound() {
                return null;
            }
            
            @Override
            public List<String> getAbilityClassNames() {
                return Collections.emptyList();
            }
            
            @Override
            public float getEntityTransmissionCoefficient() {
                return 1.0f; // 完全传导到实体
            }
            
            @Override
            public boolean isFatal() {
                return false;
            }
            
            @Override
            public float getFatalThreshold() {
                return 0.0f;
            }
        };
    }
    
    /**
     * 检查实体是否需要BodyUnit
     */
    private static boolean shouldRegisterBodyUnitForEntity(Entity entity) {
        // 根据实体类型决定是否需要骨骼BodyUnit
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
     * 修改：使用与ServerGeoModelManager一致的模型位置
     */
    private static ResourceLocation getModelLocationForEntity(Entity entity) {
        // 根据实体类型确定其模型位置
        // 注意：使用EntityType.getKey()获取实体类型的ResourceLocation
        ResourceLocation entityType = net.minecraft.world.entity.EntityType.getKey(entity.getType());
        String entityPath = entityType != null ? entityType.getPath() : "generic";
        
        // 示例：为不同类型的实体分配不同的模型
        // 修改：使用与ServerGeoModelManager一致的命名空间和路径
        if (entityPath.contains("zombie")) {
            return ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "geo/modular_zombie.geo.json");
        } else if (entityPath.contains("skeleton")) {
            return ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "geo/skeleton_model.geo.json");
        } else if (entityPath.contains("player")) {
            return ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "geo/player_model.geo.json");
        } else {
            // 默认模型
            return ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "geo/generic_model.geo.json");
        }
    }
    
    /**
     * 检查实体是否已注册BodyPart
     */
    private static boolean hasRegisteredBodyPart(long entityId) {
        return bodyUnitSystem.isEntityRegistered(entityId);
    }
    
    /**
     * 处理实体击中
     * @param entityId 实体ID
     * @param hitPoint 击中点
     * @param incomingDamage 传入伤害
     * @param damageType 伤害类型
     * @return 击中结果
     */
    public static EntityBoneBodyUnitSystem.EntityHitResult processEntityHit(
            long entityId, Vec3 hitPoint, float incomingDamage, String damageType) {
        // 注意：这里需要根据击中点确定击中的骨骼
        // 暂时使用默认骨骼名称，实际应该通过碰撞检测确定
        String boneName = "default_bone";
        return bodyUnitSystem.processEntityHit(entityId, boneName, incomingDamage, damageType);
    }
    
    /**
     * 处理实体击中（使用默认伤害类型）
     * @param entityId 实体ID
     * @param hitPoint 击中点
     * @param incomingDamage 传入伤害
     * @return 击中结果
     */
    public static EntityBoneBodyUnitSystem.EntityHitResult processEntityHit(
            long entityId, Vec3 hitPoint, float incomingDamage) {
        return processEntityHit(entityId, hitPoint, incomingDamage, "generic");
    }
}