package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.client.geometry.EntityBoneManager;
import com.catoxide.catoxidesbattlerebuild.client.models.ModelDataManager;
import com.catoxide.catoxidesbattlerebuild.client.renderer.ModularZombie2Renderer;
import com.catoxide.catoxidesbattlerebuild.client.renderer.ModularZombieRenderer;
import com.catoxide.catoxidesbattlerebuild.mob.zombie1.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.mob.zombie2.ModularZombie2;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, value = Dist.CLIENT)
public class ClientInitializer {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LogManager.clientStartup("ClientInitializer", "Client setup initializing...");
        LogManager.clientStartup("ClientInitializer", "Initializing ModelDataManager...");
        ModelDataManager.getInstance();
        LogManager.clientStartup("ClientInitializer", "Initializing EntityBoneManager...");
        EntityBoneManager.getInstance();
        LogManager.clientStartup("ClientInitializer", "Client setup complete");
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        LogManager.clientStartup("ClientInitializer", "Registering ModularZombieRenderer...");
        event.registerEntityRenderer(ModEntities.MODULAR_ZOMBIE.get(), ModularZombieRenderer::new);
        LogManager.clientStartup("ClientInitializer", "ModularZombieRenderer registered");
        LogManager.clientStartup("ClientInitializer", "Registering ModularZombie2Renderer...");
        event.registerEntityRenderer(ModEntities.MODULAR_ZOMBIE_2.get(), ModularZombie2Renderer::new);
        LogManager.clientStartup("ClientInitializer", "ModularZombie2Renderer registered");
    }
}
