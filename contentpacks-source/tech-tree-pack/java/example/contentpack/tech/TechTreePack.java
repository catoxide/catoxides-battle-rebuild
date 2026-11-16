package example.contentpack.tech;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestEvents;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import com.catoxide.catoxidesbattlerebuild.core.registry.BatchContentRegistrar;
import com.catoxide.catoxidesbattlerebuild.core.tech.TechHooks;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.tech.command.TechCommand;
import example.contentpack.tech.network.TechNetwork;
import example.contentpack.tech.network.TechOpenPayload;
import example.contentpack.tech.quest.TechQuestBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 科技树包（v2）：研究台方块 + 任务书式分页 GUI + 数据驱动条件 + 按钮提交研究。
 *
 * <p>玩法入口是研究台的右键交互（不是按键）：交互时绑定「哪一张台子」，
 * 节点的条件（{@code requirements}）、可见性（{@code visibleWhen}）与可重置解锁
 * 全部由 {@code techs/tree.json} 声明，条件类型可通过 {@link TechHooks} 扩展。
 * 科技点与解锁进度归属于具体的研究台（维度 + 坐标），完成任务或在界面里按钮提交研究项
 * 都会计入玩家最近打开的那张台子；玩家自身只保留触发器与任务完成记录。
 * <p>可选适配 Create：装了 Create 6.0 才注册应力 → 研究点转换机（接触点收敛在
 * {@code compat/CreateStressCompat}，缺失时本包其余功能不受影响）。
 */
public class TechTreePack implements ContentPack {

    private static final String TAG = "TechTreePack";

    @Override
    public String getId() {
        return "tech_tree_pack";
    }

    @Override
    public String getDisplayName() {
        return "Tech Tree Pack";
    }

    @Override
    public String getVersion() {
        return "0.1.0";
    }

    @Override
    public void register(ContentPackContext context) {
        // 1. 物品与方块（研究点 / 研究台）
        BatchContentRegistrar.Result items = context.registerItemsAndBlocksFromJar();
        LogManager.serverInfo(TAG, "items/blocks registered: items={}, blocks={}",
                items.items(), items.blocks());
        if (!items.problems().isEmpty()) {
            LogManager.serverError(TAG, "item/block registration problems: {}", items.problems());
        }

        // 2. 树数据：解析 + 自检（重复 id / 缺前置 / 成环 / 非法成本 / 台子层级均打 ERROR）
        TechTree tree = TechTreeLoader.load();
        TechService.install(tree);
        LogManager.serverInfo(TAG, "tech tree loaded: {} node(s), {} research item(s), "
                        + "{} page(s), table maxTier={}",
                tree.nodes().size(), tree.research().size(), tree.pages().size(),
                tree.table().maxTier());

        // 2.1 条件扩展：其它包消费该 capability 注册自定义条件类型
        context.provide(TechHooks.REQUIREMENTS, TechRequirementEngine::registerExtension);
        NeoForge.EVENT_BUS.addListener((TechHooks.TriggerEvent event) ->
                TechService.fireTrigger(event.player, event.trigger));

        // 3. 个人任务：条件满足即发放，解锁节点即完成（可重复提交走界面按钮，不再生成任务）
        QuestSystem questSystem = QuestSystem.getInstance();
        TechQuestBridge.registerFactories(questSystem);
        TechQuestBridge.registerQuests(context, tree);
        QuestSystem.installDefaultDriver();

        // 3.1 完成任务 → 发放科技点（虚拟余额，直接同步给玩家）
        NeoForge.EVENT_BUS.addListener(TechTreePack::onQuestCompleted);

        // 4. 网络（S2C 状态/开界面/台子层级；C2S 请求/解锁/提交/升级）
        TechNetwork.register(context.getModEventBus());

        // 5. 右键研究台打开界面（双端拦截，服务端校验后下发 open）
        NeoForge.EVENT_BUS.addListener(TechTreePack::onRightClickResearchTable);

        // 6. 存档：进入世界时确保 SavedData 已加载（overworld 承载）
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level
                    && level.dimension().equals(Level.OVERWORLD)) {
                TechSavedData.get(level);
            }
        });

        // 7. 登录即同步一次进度（界面晚开也能拿到最新状态）
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                TechService.sync(player);
            }
        });

        // 8. /techtree 调试命令（包内自定义，暂无全局命令规范）
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                TechCommand.register(event.getDispatcher()));

        // 9. 可选联动：Create 应力 → 研究点。桥接类只在 gate 通过后首次引用
        //    （design/PACK_DEPS_AND_LINKAGE.md §4 软依赖三件套）。
        if (ModList.get().isLoaded("create")) {
            example.contentpack.tech.compat.CreateStressCompat.register(context);
        } else {
            LogManager.serverInfo(TAG,
                    "Create not installed: stress converter disabled");
        }

        LogManager.serverInfo(TAG, "TechTreePack registered");
    }

    private static void onQuestCompleted(QuestEvents.QuestCompletedEvent event) {
        // 记录已完成任务：quest 条件据此判定（任务实例可能被清理/重建）
        TechService.addCompletedQuest(event.player, event.quest.getDefinition().id());
        int points = TechService.questPointReward(event.quest.getDefinition().id());
        if (points > 0) {
            TechService.addPoints(event.player, points);
        }
    }

    private static void onRightClickResearchTable(PlayerInteractEvent.RightClickBlock event) {
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!TechBlocks.isResearchTable(state)) {
            return;
        }
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);

        if (event.getLevel() instanceof ServerLevel
                && event.getEntity() instanceof ServerPlayer player) {
            int tier = TechBlocks.tier(state);
            TechRequirementEngine.Context context =
                    new TechRequirementEngine.Context(event.getPos(), tier);
            TechService.bindTable(player, event.getPos(), tier);
            TechService.sync(player, context);
            PacketDistributor.sendToPlayer(player,
                    new TechOpenPayload(event.getPos(), tier));
        }
    }
}
