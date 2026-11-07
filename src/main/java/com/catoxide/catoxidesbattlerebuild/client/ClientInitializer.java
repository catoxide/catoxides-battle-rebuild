package com.catoxide.catoxidesbattlerebuild.client;

import cn.solarmoon.spark_core.util.PerspectiveBakedModel;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.client.geometry.EntityBoneManager;
import com.catoxide.catoxidesbattlerebuild.client.models.ModelDataManager;
import com.catoxide.catoxidesbattlerebuild.client.renderer.DataDrivenMobRenderer;
import com.catoxide.catoxidesbattlerebuild.client.renderer.IronSwordRenderer;
import com.catoxide.catoxidesbattlerebuild.client.renderer.ModularZombie2Renderer;
import com.catoxide.catoxidesbattlerebuild.client.renderer.ModularZombieRenderer;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackGraph;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackLoader;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackRegistry;
import com.catoxide.catoxidesbattlerebuild.mob.zombie1.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.mob.zombie2.ModularZombie2;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import com.catoxide.catoxidesbattlerebuild.registry.ModWeapons;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, value = Dist.CLIENT)
public class ClientInitializer {

    private static final ResourceLocation IRON_SWORD_ID =
            ResourceLocation.fromNamespaceAndPath(CatoxidesBattleRebuildConstants.MODID, "iron_sword_weapon");
    private static final ModelResourceLocation IRON_SWORD_MODEL = ModelResourceLocation.inventory(IRON_SWORD_ID);
    private static final ModelResourceLocation IRON_SWORD_IN_HAND_MODEL =
            ModelResourceLocation.standalone(IRON_SWORD_ID.withPrefix("item/").withSuffix("_in_hand"));
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


        // 内容包渲染器注册：pack 通过 registerClientRenderers 自注册（主 mod 不依赖具体类）
        // 被隔离的包跳过（含硬依赖链上被连带取消的）—— 它们的内容不应该再出现在屏幕上。
        for (ContentPackRegistry.LoadedPack loaded : ContentPackRegistry.getAllPacks()) {
            if (ContentPackGraph.isQuarantined(loaded.id())) {
                LogManager.clientWarn("ClientInitializer", "Skipping client renderers for cancelled pack '{}'", loaded.id());
                continue;
            }
            try {
                loaded.pack().registerClientRenderers((holder, provider) ->
                        event.registerEntityRenderer((net.minecraft.world.entity.EntityType) holder.get(), provider));
            } catch (Exception e) {
                LogManager.clientWarn("ClientInitializer", "Failed to register client renderers for pack '{}': {}",
                        loaded.id(), e.getMessage());
            }
        }

        // 数据驱动实体通用渲染器（所有 JSON 定义实体共用，无需逐个写渲染器）
        var contentPackContext = CatoxidesBattleRebuild.getContentPackContext();
        if (contentPackContext != null) {
            for (var holder : contentPackContext.getDataDrivenEntities()) {
                event.registerEntityRenderer(holder.get(), DataDrivenMobRenderer::new);
                LogManager.clientStartup("ClientInitializer", "DataDrivenMobRenderer registered for " + holder.getKey());
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

    // 不用 spark_core 的 ItemInHandModelRegisterEvent：它在 ITEM RegisterEvent 中途同步发出，
    // 依赖 spark_core 的 mod 其物品此时必然还没注册（DeferredHolder 未绑定）。改为烘焙阶段自行包装。
    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(IRON_SWORD_IN_HAND_MODEL);
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        BakedModel base = event.getModels().get(IRON_SWORD_MODEL);
        BakedModel inHand = event.getModels().get(IRON_SWORD_IN_HAND_MODEL);
        if (base != null && inHand != null) {
            event.getModels().put(IRON_SWORD_MODEL, new PerspectiveBakedModel(base, inHand));
            LogManager.clientStartup("ClientInitializer", "IronSwordWeapon perspective model wrapped");
        }
    }
}
