package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.client.renderer.HitboxRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class RenderEventHandler {

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // 在渲染完透明块之后渲染，这样可以确保我们的渲染在大多数内容之后，避免被遮挡
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            try {
                HitboxRenderer.getInstance().renderAllHitboxes(
                        event.getPoseStack(),
                        event.getLevelRenderer().renderBuffers().bufferSource(),
                        event.getPartialTick()
                );
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}