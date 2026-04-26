package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild;
import com.catoxide.catoxidesbattlerebuild.client.bodypart.ClientEntityBodySystem;
import com.catoxide.catoxidesbattlerebuild.client.manager.ClientHitboxManager;
import com.catoxide.catoxidesbattlerebuild.client.models.ClientModelManager;
import com.catoxide.catoxidesbattlerebuild.client.renderer.HitboxDebugRenderer;
import com.catoxide.catoxidesbattlerebuild.client.renderer.ModularZombieRenderer;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端初始化器
 * 负责初始化客户端的各个系统
 */
@Mod.EventBusSubscriber(modid = "catoxidesbattlerebuild", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientInitializer.class);
    
    private static boolean initialized = false;
    
    /**
     * 客户端设置事件
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("[ClientInitializer] Starting client initialization...");
        
        try {
            // 注册实体渲染器
            EntityRenderers.register(ModEntities.MODULAR_ZOMBIE.get(), ModularZombieRenderer::new);
            LOGGER.info("[ClientInitializer] ModularZombieRenderer registered");
            
            // 初始化模型管理器
            ClientModelManager.getInstance().initialize();
            LOGGER.info("[ClientInitializer] ClientModelManager initialized");
            
            // 初始化受击盒管理器
            ClientHitboxManager.getInstance().initialize();
            LOGGER.info("[ClientInitializer] ClientHitboxManager initialized");
            
            // 初始化实体身体部位系统
            ClientEntityBodySystem.getInstance().initialize();
            LOGGER.info("[ClientInitializer] ClientEntityBodySystem initialized");
            
            // 调试渲染器不需要初始化（静态方法类）
            LOGGER.info("[ClientInitializer] HitboxDebugRenderer ready (static methods)");
            
            initialized = true;
            LOGGER.info("[ClientInitializer] Client initialization completed successfully");
            
        } catch (Exception e) {
            LOGGER.error("[ClientInitializer] Failed to initialize client systems", e);
            initialized = false;
        }
    }
    
    /**
     * 检查是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 实体加入世界事件处理器
     */
    @Mod.EventBusSubscriber(modid = "catoxidesbattlerebuild", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class EntityEventHandler {
        private static final Logger LOGGER = LoggerFactory.getLogger(EntityEventHandler.class);
        
        @SubscribeEvent
        public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
            if (!initialized) {
                return;
            }
            
            Entity entity = event.getEntity();
            if (entity.level().isClientSide) {
                LOGGER.debug("[EntityEventHandler] Entity joined level: id={}, type={}", 
                        entity.getId(), entity.getType());
                
                // 检查实体是否有受击盒组件（通过检查实体类型或自定义标签）
                // TODO: 实现更精确的组件检查机制
                // if (entity.hasData(com.catoxide.catoxidesbattlerebuild.server.component.BoneHitboxComponent.class)) {
                //     LOGGER.info("[EntityEventHandler] Entity has hitbox component: id={}", entity.getId());
                //     
                //     // 注册实体到受击盒管理器
                //     // TODO: 需要从实体获取模型位置
                //     // ClientHitboxManager.getInstance().registerEntity(entity.getUUID(), entity, modelLocation);
                // }
            }
        }
    }
    
    /**
     * 渲染事件处理器
     */
    @Mod.EventBusSubscriber(modid = "catoxidesbattlerebuild", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class RenderEventHandler {
        private static final Logger LOGGER = LoggerFactory.getLogger(RenderEventHandler.class);
        
        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            if (!initialized) {
                return;
            }
            
            // 只在实体渲染之后渲染，这样可以确保我们的渲染在实体之上
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
                return;
            }
            
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            
            try {
                // 更新所有实体的受击盒
                ClientHitboxManager.getInstance().getAllBoneCollections().forEach((entityUuid, boneCollections) -> {
                    ClientHitboxManager.getInstance().updateEntityHitboxes(entityUuid);
                });
                
                // 渲染调试信息（使用BufferBuilder管线）
                if (HitboxDebugRenderer.isDebugEnabled()) {
                    // 获取相机位置
                    Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
                    
                    // 调用HitboxDebugRenderer渲染（内部会使用BufferBuilder）
                    HitboxDebugRenderer.renderAllHitboxes(
                        event.getPoseStack(),
                        camPos,
                        event.getPartialTick()
                    );
                    
                    LOGGER.debug("[RenderEventHandler] Debug hitboxes rendered");
                }
                
            } catch (Exception e) {
                LOGGER.error("[RenderEventHandler] Error during render", e);
            }
        }
    }
    
    /**
     * 按键事件处理器
     * 用于监听F4键来启用/禁用调试渲染（简化版本，用于测试）
     */
    @Mod.EventBusSubscriber(modid = "catoxidesbattlerebuild", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class KeyInputEventHandler {
        private static final Logger LOGGER = LoggerFactory.getLogger(KeyInputEventHandler.class);
        
        @SubscribeEvent
        public static void onKeyInput(net.minecraftforge.client.event.InputEvent.Key event) {
            if (!initialized) {
                return;
            }
            
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }
            
            // 检查是否按下了F4键（键码61）
            if (event.getKey() == 61 && event.getAction() == 1) {
                boolean currentEnabled = HitboxDebugRenderer.isDebugEnabled();
                HitboxDebugRenderer.setDebugEnabled(!currentEnabled);
                
                mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                        "[Catoxide's Battle Rebuild] Debug hitbox rendering " + 
                        (!currentEnabled ? "enabled" : "disabled")
                    ),
                    true
                );
                
                LOGGER.info("[KeyInputEventHandler] F4 pressed, debug rendering toggled to {}", 
                        !currentEnabled);
            }
        }
    }
    
    /**
     * 客户端Tick事件处理器
     * 用于在游戏启动后自动启用调试渲染（用于测试）
     */
    @Mod.EventBusSubscriber(modid = "catoxidesbattlerebuild", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientTickEventHandler {
        private static final Logger LOGGER = LoggerFactory.getLogger(ClientTickEventHandler.class);
        private static boolean autoEnabled = false;
        
        @SubscribeEvent
        public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
            if (!initialized || autoEnabled) {
                return;
            }
            
            if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.level != null) {
                    // 自动启用调试渲染（用于测试）
                    HitboxDebugRenderer.setDebugEnabled(true);
                    autoEnabled = true;
                    
                    mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                            "[Catoxide's Battle Rebuild] Debug hitbox rendering auto-enabled for testing"
                        ),
                        true
                    );
                    
                    LOGGER.info("[ClientTickEventHandler] Debug rendering auto-enabled");
                }
            }
        }
    }
}