package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import com.catoxide.catoxidesbattlerebuild.client.renderer.HitboxPartRenderer;
import com.catoxide.catoxidesbattlerebuild.client.renderer.ModularZombieRenderer;

@Mod.EventBusSubscriber(modid = CatoxidesBattleRebuild.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetup {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 在这里注册任何客户端特定的设置
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.MODULAR_ZOMBIE.get(), ModularZombieRenderer::new);
        // 注册 HitboxPart 的空渲染器
        event.registerEntityRenderer(ModEntities.HITBOX_PART.get(), HitboxPartRenderer::new);
    }
}