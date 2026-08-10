package com.catoxide.catoxidesbattlerebuild;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackLoader;
import com.catoxide.catoxidesbattlerebuild.network.ModNetworkHandler;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import com.catoxide.catoxidesbattlerebuild.registry.ModEvents;
import com.catoxide.catoxidesbattlerebuild.registry.ModSounds;
import com.catoxide.catoxidesbattlerebuild.registry.ModWeapons;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.ServerBoneSyncManager;
import com.catoxide.catoxidesbattlerebuild.server.damage.DamageProcessor;
import com.catoxide.catoxidesbattlerebuild.server.quest.QuestSyncManager;
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
    private net.neoforged.fml.ModContainer modContainer;

    public CatoxidesBattleRebuild(IEventBus modEventBus, net.neoforged.fml.ModContainer modContainer) {
        LogManager.serverStartup("CatoxidesBattleRebuild", "Initializing mod...");
        this.modContainer = modContainer;

        CatoxidesBattleRebuildConstants.init();
        ModNetworkHandler.init();
        EntityBoneSystem.getInstance();
        DamageProcessor.getInstance();
        // 骨骼位置同步管理器（懒单例：必须主动初始化，否则事件监听不注册 → 客户端无骨骼数据）
        ServerBoneSyncManager.getInstance();
        // 任务状态同步管理器（懒单例：同上，否则客户端无任务数据）
        QuestSyncManager.getInstance();
        // 任务调试命令 /quest
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent e) ->
                        com.catoxide.catoxidesbattlerebuild.core.quest.command.QuestCommand.register(e.getDispatcher()));

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);
        ModWeapons.ITEMS.register(modEventBus);

        // 耐久能力框架（可变耐久 DataComponent 注册）
        com.catoxide.catoxidesbattlerebuild.core.durability.DurabilityCapabilities.DATA_COMPONENT_TYPES.register(modEventBus);

        // 现有武器接入耐久端口（可配置有限耐久；未来迁 contentpack 后由武器包自注册）
        modEventBus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent e) ->
                com.catoxide.catoxidesbattlerebuild.core.durability.DurabilitySupport.registerVariableDurability(
                        e,
                        com.catoxide.catoxidesbattlerebuild.registry.ModWeapons.IRON_SWORD_WEAPON.get(),
                        com.catoxide.catoxidesbattlerebuild.registry.ModWeapons.CUSTOM_BOW.get()));

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
                    ModEntities.ENTITIES,
                    modEventBus,
                    modContainer
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