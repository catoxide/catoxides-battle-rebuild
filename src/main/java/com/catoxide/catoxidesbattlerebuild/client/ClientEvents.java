package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.client.model.ModularZombieModel;
import com.catoxide.catoxidesbattlerebuild.client.renderer.HitboxDebugRenderer;
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

        HitboxDebugRenderer.setDebugEnabled(true);
        LogManager.clientInfo("ClientEvents", "HitboxDebugRenderer enabled");
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }

        if (!HitboxDebugRenderer.isDebugEnabled()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

        poseStack.pushPose();
        
        poseStack.translate(-camera.getPosition().x, -camera.getPosition().y, -camera.getPosition().z);

        HitboxDebugRenderer.renderDebugCubes(poseStack, bufferSource);

        poseStack.popPose();
    }
}