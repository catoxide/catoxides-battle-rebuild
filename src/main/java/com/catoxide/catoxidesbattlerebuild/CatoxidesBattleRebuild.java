package com.catoxide.catoxidesbattlerebuild;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackLoader;
import com.catoxide.catoxidesbattlerebuild.network.ModNetworkHandler;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import com.catoxide.catoxidesbattlerebuild.registry.ModEvents;
import com.catoxide.catoxidesbattlerebuild.registry.ModSounds;
import com.catoxide.catoxidesbattlerebuild.registry.ModWeapons;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.server.damage.DamageProcessor;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

@Mod(CatoxidesBattleRebuildConstants.MODID)
public class CatoxidesBattleRebuild {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CatoxidesBattleRebuildConstants.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CatoxidesBattleRebuildConstants.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CatoxidesBattleRebuildConstants.MODID);

    private static ContentPackContext contentPackContext;

    public CatoxidesBattleRebuild(IEventBus modEventBus) {
        LogManager.serverStartup("CatoxidesBattleRebuild", "Initializing mod...");

        CatoxidesBattleRebuildConstants.init();
        ModNetworkHandler.init();
        EntityBoneSystem.getInstance();
        DamageProcessor.getInstance();

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);
        ModWeapons.ITEMS.register(modEventBus);

        loadContentPacks(modEventBus);

        LogManager.serverInfo("CatoxidesBattleRebuild", "Mod initialization complete");
    }

    private void loadContentPacks(IEventBus modEventBus) {
        LogManager.serverStartup("CatoxidesBattleRebuild", "Loading ContentPacks...");

        List<String> scanDirs = List.of(
                "contentpacks",
                "mods/contentpacks"
        );

        int loaded = ContentPackLoader.loadAll(scanDirs, CatoxidesBattleRebuildConstants.MOD_VERSION);

        if (loaded > 0) {
            contentPackContext = new ContentPackContext(
                    CatoxidesBattleRebuildConstants.MODID,
                    ModSounds.SOUNDS,
                    ModEntities.ENTITIES
            );
            ContentPackLoader.executeAllRegistries(contentPackContext);
            ModEvents.setContentPackContext(contentPackContext);
            LogManager.serverStartup("CatoxidesBattleRebuild", "ContentPacks loaded: " + loaded);
        } else {
            LogManager.serverInfo("CatoxidesBattleRebuild", "No ContentPacks found in %s", scanDirs);
        }
    }

    public static ContentPackContext getContentPackContext() {
        return contentPackContext;
    }
}