package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        com.catoxide.catoxidesbattlerebuild.client.renderer.BoneDebugRenderer.render(
                event.getPoseStack(),
                event.getCamera().getPosition(),
                partialTick
        );
        // 剑碰撞箱可视化（仅手持本 Mod 铁剑时绘制，见类注释）
        com.catoxide.catoxidesbattlerebuild.client.renderer.SwordHitboxDebugRenderer.render(
                event.getPoseStack(),
                event.getCamera().getPosition(),
                partialTick
        );
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        com.catoxide.catoxidesbattlerebuild.client.renderer.SwordHitboxDebugRenderer.renderHud(event.getGuiGraphics());
    }
}