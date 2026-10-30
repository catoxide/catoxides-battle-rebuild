package com.catoxide.catoxidesbattlerebuild;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackConfigBridge;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackLoader;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestBuiltinComponents;
import com.catoxide.catoxidesbattlerebuild.network.ModNetworkHandler;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import com.catoxide.catoxidesbattlerebuild.registry.ModEvents;
import com.catoxide.catoxidesbattlerebuild.registry.ModSounds;
import com.catoxide.catoxidesbattlerebuild.registry.ModWeapons;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.ServerBoneSyncManager;
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

        // 内置配置屏入口（仅客户端）：所有包的配置都挂在主 mod 上，这里只装一个扩展点
        if (CatoxidesBattleRebuildConstants.CLIENT) {
            com.catoxide.catoxidesbattlerebuild.client.ConfigScreenBridge.register(modContainer);
        }

        CatoxidesBattleRebuildConstants.init();
        ModNetworkHandler.init();
        EntityBoneSystem.getInstance();
        // 骨骼位置同步管理器（懒单例：必须主动初始化，否则事件监听不注册 → 客户端无骨骼数据）
        ServerBoneSyncManager.getInstance();
        // 任务系统（M3 已迁入 quest-test-pack）：同步管理器/命令/按键/HUD/网络
        // 均由 QuestTestPack.register() 显式注册；不装任务包时 core 侧零残留（R3）

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);
        ModWeapons.ITEMS.register(modEventBus);

        // 耐久能力框架（可变耐久 DataComponent 注册）
        com.catoxide.catoxidesbattlerebuild.core.durability.DurabilityCapabilities.DATA_COMPONENT_TYPES.register(modEventBus);

        // 现有武器接入耐久端口（可配置有限耐久；未来迁 contentpack 后由武器包自注册）
        // 弓（CUSTOM_BOW）M6 起由 melee-combat-pack 入口自注册耐久端口；剑留 core，此处仍注册
        modEventBus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent e) ->
                com.catoxide.catoxidesbattlerebuild.core.durability.DurabilitySupport.registerVariableDurability(
                        e,
                        com.catoxide.catoxidesbattlerebuild.registry.ModWeapons.IRON_SWORD_WEAPON.get()));

        // context 无条件创建：主 mod 自身也可用批量注册接口（registerItemsAndBlocks 等），
        // 包缺席时（R3）接口只是没有包内容可注册，行为不变。
        contentPackContext = new ContentPackContext(
                CatoxidesBattleRebuildConstants.MODID,
                ModSounds.SOUNDS,
                ModEntities.ENTITIES,
                modEventBus,
                modContainer
        );
        ModEvents.setContentPackContext(contentPackContext);

        // 通用任务组件（proximity_giver / item_present / item_submit）：纯 JSON 任务包不必自写工厂。
        // 必须在包 register() 之前注册 —— 包注册同名类型时后注册者生效（可覆盖内置实现）。
        QuestBuiltinComponents.register();

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
            // 配置声明必须先于 executeAllRegistries：包的 register() 里要能 ctx.config(id)
            ContentPackConfigBridge.registerAll(contentPackContext);
            ContentPackLoader.executeAllRegistries(contentPackContext);
            LogManager.serverStartup("CatoxidesBattleRebuild", "ContentPacks loaded: " + loaded);
        } else {
            LogManager.serverInfo("CatoxidesBattleRebuild", "No ContentPacks found in %s", scanDirs);
        }
    }

    public static ContentPackContext getContentPackContext() {
        return contentPackContext;
    }
}
