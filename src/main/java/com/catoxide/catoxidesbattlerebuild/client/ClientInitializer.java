package com.catoxide.catoxidesbattlerebuild.client;

import cn.solarmoon.spark_core.event.ItemInHandModelRegisterEvent;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.client.geometry.EntityBoneManager;
import com.catoxide.catoxidesbattlerebuild.client.models.ModelDataManager;
import com.catoxide.catoxidesbattlerebuild.client.renderer.IronSwordRenderer;
import com.catoxide.catoxidesbattlerebuild.client.renderer.ModularZombie2Renderer;
import com.catoxide.catoxidesbattlerebuild.client.renderer.ModularZombieRenderer;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackLoader;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackRegistry;
import com.catoxide.catoxidesbattlerebuild.mob.zombie1.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.mob.zombie2.ModularZombie2;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import com.catoxide.catoxidesbattlerebuild.registry.ModWeapons;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.zombie3.ModularZombie3Pack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, value = Dist.CLIENT)
public class ClientInitializer {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LogManager.clientStartup("ClientInitializer", "Client setup initializing...");
        LogManager.clientStartup("ClientInitializer", "Initializing ModelDataManager...");
        ModelDataManager.getInstance();
        LogManager.clientStartup("ClientInitializer", "Initializing EntityBoneManager...");
        EntityBoneManager.getInstance();

        event.enqueueWork(() -> {
            LogManager.clientStartup("ClientInitializer", "Loading pending Spark-Core packages...");
            ContentPackLoader.loadPendingSparkPackages(true);
            LogManager.clientStartup("ClientInitializer", "Spark-Core packages loaded");
        });

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

        for (ContentPackRegistry.LoadedPack loaded : ContentPackRegistry.getAllPacks()) {
            if (loaded.pack() instanceof ModularZombie3Pack pack) {
                LogManager.clientStartup("ClientInitializer", "Registering ModularZombie3Renderer from ContentPack '" + loaded.id() + "'...");
                event.registerEntityRenderer(pack.getModularZombie3().get(), example.contentpack.zombie3.ModularZombie3Renderer::new);
                LogManager.clientStartup("ClientInitializer", "ModularZombie3Renderer registered");
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        LogManager.clientStartup("ClientInitializer", "Registering IronSwordRenderer...");
        event.registerItem(new IClientItemExtensions() {
            private final IronSwordRenderer renderer = new IronSwordRenderer();

            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return renderer;
            }
        }, ModWeapons.IRON_SWORD_WEAPON.get());
        LogManager.clientStartup("ClientInitializer", "IronSwordRenderer registered");
    }

    @SubscribeEvent
    public static void onRegisterInHandModel(ItemInHandModelRegisterEvent event) {
        LogManager.clientStartup("ClientInitializer", "Registering IronSwordWeapon in-hand model...");
        event.addInHandModel(ModWeapons.IRON_SWORD_WEAPON.get());
        LogManager.clientStartup("ClientInitializer", "IronSwordWeapon in-hand model registered");
    }
}
