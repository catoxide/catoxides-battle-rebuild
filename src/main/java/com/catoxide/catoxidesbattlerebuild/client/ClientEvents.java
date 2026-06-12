package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.client.model.ModularZombieModel;
import com.catoxide.catoxidesbattlerebuild.client.renderer.ModularZombieRenderer;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.Camera;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LogManager.clientStartup("ClientEvents", "Starting client setup...");

        event.enqueueWork(() -> {
            try {
                net.minecraft.client.renderer.entity.EntityRenderers.register(
                    ModEntities.MODULAR_ZOMBIE.get(), ModularZombieRenderer::new);
                LogManager.clientInfo("ClientEvents", "ModularZombieRenderer registered successfully");
            } catch (Exception e) {
                LogManager.clientError("ClientEvents", "Failed to register ModularZombieRenderer", e);
            }
        });

        LogManager.clientInfo("ClientEvents", "Client setup complete (HitboxDebugRenderer archived)");
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // HitboxDebugRenderer 功能已归档到 Deprecated&References
        // 如需调试功能，请参考 Deprecated&References 目录
    }
}