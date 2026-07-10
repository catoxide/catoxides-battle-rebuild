package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.client.model.ModularZombieModel;
import com.catoxide.catoxidesbattlerebuild.client.renderer.ModularZombie2Renderer;
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
            
            try {
                net.minecraft.client.renderer.entity.EntityRenderers.register(
                    ModEntities.MODULAR_ZOMBIE_2.get(), ModularZombie2Renderer::new);
                LogManager.clientInfo("ClientEvents", "ModularZombie2Renderer registered successfully");
            } catch (Exception e) {
                LogManager.clientError("ClientEvents", "Failed to register ModularZombie2Renderer", e);
            }
        });

        LogManager.clientInfo("ClientEvents", "Client setup complete (HitboxDebugRenderer archived)");
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // 用 AFTER_PARTICLES：在所有实体/粒子渲染完之后，调试框不会被遮挡
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        com.catoxide.catoxidesbattlerebuild.client.renderer.ServerBoneDebugRenderer.render(
                event.getPoseStack(),
                event.getCamera().getPosition(),
                event.getPartialTick().getGameTimeDeltaPartialTick(false)
        );
    }
}