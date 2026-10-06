package example.contentpack.questtest;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestDefinition;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestDefinitionParser;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestEvents;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import com.catoxide.catoxidesbattlerebuild.core.quest.IQuestGiver;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import example.contentpack.questtest.client.ClientQuestCache;
import example.contentpack.questtest.client.QuestHUD;
import example.contentpack.questtest.client.QuestKeyBindings;
import example.contentpack.questtest.command.QuestCommand;
import example.contentpack.questtest.network.QuestSyncPacket;
import example.contentpack.questtest.network.QuestToggleStarPacket;
import example.contentpack.questtest.server.QuestSyncManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 测试内容包：验证任务基座（QuestSystem）完整链路 + JSON 数据驱动定义。
 * <p>验证目标：
 * <ul>
 *   <li>registerQuest 定义注册 + 任务树（主任务容器 + 2 子任务，Java 构造）</li>
 *   <li>quests/quests.json → {@link QuestDefinitionParser} → registerQuest（JSON 数据驱动）</li>
 *   <li>registerGoalType / registerGiverType 工厂注册</li>
 *   <li>giver 发放（玩家靠近起始点 → giveQuest）</li>
 *   <li>进度驱动（tick 更新 → checkCompletion）</li>
 *   <li>子任务完成 → 容器父任务完成（任务树传播）</li>
 *   <li>事件广播（Given/Completed/Failed/Removed → 日志）</li>
 * </ul>
 */
public class QuestTestPack implements ContentPack {

    private static final String TAG = "QuestTestPack";

    @Override
    public String getId() {
        return "quest_test_pack";
    }

    @Override
    public String getDisplayName() {
        return "Quest Test Pack";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void register(ContentPackContext context) {
        QuestSystem questSystem = QuestSystem.getInstance();

        // ===== 1. 组件工厂注册 =====
        questSystem.registerGoalType("proximity", json -> {
            JsonArray t = json.getAsJsonArray("target");
            Vec3 target = new Vec3(t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.get(2).getAsDouble());
            float radius = json.has("radius") ? json.get("radius").getAsFloat() : 5f;
            return new ProximityGoal(target, radius);
        });
        questSystem.registerGiverType("proximity_giver", json -> {
            JsonArray c = json.getAsJsonArray("center");
            Vec3 center = new Vec3(c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble());
            float radius = json.has("radius") ? json.get("radius").getAsFloat() : 10f;
            return new StartGiver(center, radius);
        });
        questSystem.registerGoalType("kill", json -> {
            String entity = json.get("entity").getAsString();
            float count = json.has("count") ? json.get("count").getAsFloat() : 1f;
            return new KillGoal(entity, count);
        });
        questSystem.registerGoalType("craft", json -> {
            String item = json.get("item").getAsString();
            float count = json.has("count") ? json.get("count").getAsFloat() : 1f;
            return new CraftGoal(item, count);
        });
        LogManager.serverInfo(TAG, "Registered 'proximity'/'proximity_giver'/'kill'/'craft' factories");

        // ===== 2. Java 构造的任务树：main（容器） + sub1 + sub2 =====
        Vec3 start = new Vec3(0, 64, 0);
        Vec3 pointA = new Vec3(100, 64, 100);
        Vec3 pointB = new Vec3(200, 64, 200);

        QuestDefinition sub1 = new QuestDefinition(
                context.id("test_sub1"),
                Component.literal("巡视北门"),
                Component.literal("前往 (100, 64, 100)"),
                List.of(),
                List.of(new ProximityGoal(pointA, 5f)),
                List.of(), List.of(), List.of(),
                pointA, 1f);
        QuestDefinition sub2 = new QuestDefinition(
                context.id("test_sub2"),
                Component.literal("巡视东门"),
                Component.literal("前往 (200, 64, 200)"),
                List.of(),
                List.of(new ProximityGoal(pointB, 5f)),
                List.of(), List.of(), List.of(),
                pointB, 1f);
        QuestDefinition main = new QuestDefinition(
                context.id("test_main"),
                Component.literal("基地巡视"),
                Component.literal("完成两个子任务"),
                List.of(new StartGiver(start, 10f)),
                List.of(),
                List.of(), List.of(),
                List.of(sub1, sub2),
                null, 0f);
        context.registerQuest(main);
        LogManager.serverInfo(TAG, "Registered Java quest tree: main={}, children={}",
                main.id(), main.children().size());

        // ===== 3. JSON 数据驱动任务（验证 QuestDefinitionParser）=====
        try (InputStream in = getClass().getResourceAsStream("/quests/quests.json")) {
            if (in == null) {
                LogManager.serverError(TAG, "quests/quests.json not found in jar");
            } else {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                QuestDefinition jsonQuest = QuestDefinitionParser.parse(json, context.getModId());
                context.registerQuest(jsonQuest);
            }
        } catch (Exception e) {
            LogManager.serverError(TAG, "Failed to parse quests/quests.json: {}", e.getMessage(), e);
        }

        // ===== 3b. 事件桥驱动的击杀任务（验证 QuestEventBridge → KillGoal 链路）=====
        QuestDefinition killQuest = new QuestDefinition(
                context.id("test_kill"),
                Component.literal("僵尸猎手"),
                Component.literal("击杀 2 只僵尸（事件桥驱动）"),
                List.of(new StartGiver(start, 15f)),
                List.of(new KillGoal("minecraft:zombie", 2f)),
                List.of(), List.of(), List.of(),
                null, 0f);
        context.registerQuest(killQuest);
        LogManager.serverInfo(TAG, "Registered kill quest: {}", killQuest.id());

        // ===== 3c. 合成任务（验证事件桥 ItemCraftedEvent → CraftGoal 链路）=====
        QuestDefinition craftQuest = new QuestDefinition(
                context.id("test_craft"),
                Component.literal("工匠入门"),
                Component.literal("合成 1 个工作台（事件桥驱动）"),
                List.of(new StartGiver(start, 20f)),
                List.of(new CraftGoal("minecraft:crafting_table", 1f)),
                List.of(), List.of(), List.of(),
                null, 0f);
        context.registerQuest(craftQuest);
        LogManager.serverInfo(TAG, "Registered craft quest: {}", craftQuest.id());

        // ===== 4. 事件日志 =====
        NeoForge.EVENT_BUS.addListener((QuestEvents.QuestGivenEvent e) ->
                LogManager.serverInfo(TAG, "EVENT Given: {} to {}", e.quest.getDefinition().id(), e.player.getName().getString()));
        NeoForge.EVENT_BUS.addListener((QuestEvents.QuestProgressEvent e) ->
                LogManager.serverDebug(TAG, "EVENT Progress: {}", e.quest.getDefinition().id()));
        NeoForge.EVENT_BUS.addListener((QuestEvents.QuestCompletedEvent e) ->
                LogManager.serverInfo(TAG, "EVENT Completed: {}", e.quest.getDefinition().id()));
        NeoForge.EVENT_BUS.addListener((QuestEvents.QuestFailedEvent e) ->
                LogManager.serverInfo(TAG, "EVENT Failed: {} reason={}", e.quest.getDefinition().id(), e.reason));
        NeoForge.EVENT_BUS.addListener((QuestEvents.QuestRemovedEvent e) ->
                LogManager.serverInfo(TAG, "EVENT Removed: {}", e.quest.getDefinition().id()));

        // ===== 5. tick 驱动：对所有已注册定义检查发放 + 进度更新 =====
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            var server = e.getServer();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                UUID uid = player.getUUID();
                // 发放检查（每个定义：无激活实例且 giver 条件满足 → 发放）
                for (QuestDefinition def : questSystem.getDefinitions()) {
                    boolean hasActive = questSystem.getPlayerQuests(uid).stream()
                            .anyMatch(q -> q.getDefinition().id().equals(def.id()) && q.isActive());
                    if (!hasActive && def.givers().stream().anyMatch(g -> g.canGive(player))) {
                        questSystem.giveQuest(player, def.id(), "tick:" + def.id().getPath());
                    }
                }
                // 进度驱动：checkAllQuests 递归检查整棵树（含子任务）→
                // 完成/失败条件被评估（ProximityGoal 到点即完成）
                questSystem.checkAllQuests(player);
            }
        });

        // ===== 6. /quest 命令（M3 迁移：由 core 主类移入；不装包时命令不存在，R3）=====
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) ->
                QuestCommand.register(e.getDispatcher()));

        // ===== 7. 任务网络（M3 迁移：V1 先例——包自己注册 payload）=====
        // registrar("1") 无 modid 参数 ⇒ 归入主 mod 协议 "1"；payload id 保持
        // catoxidesbattlerebuild:* 不变（B6：命名空间不随包走）。
        context.getModEventBus().addListener(RegisterPayloadHandlersEvent.class, e -> {
            PayloadRegistrar reg = e.registrar("1");
            reg.playToClient(QuestSyncPacket.TYPE, QuestSyncPacket.STREAM_CODEC,
                    (p, c) -> c.enqueueWork(() ->
                            ClientQuestCache.getInstance().update(c.player().getUUID(), p.entries())));
            reg.playToServer(QuestToggleStarPacket.TYPE, QuestToggleStarPacket.STREAM_CODEC,
                    (p, c) -> c.enqueueWork(() -> {
                        ServerPlayer player = (ServerPlayer) c.player();
                        QuestSystem qs = QuestSystem.getInstance();
                        for (QuestInstance root : qs.getPlayerQuests(player.getUUID())) {
                            for (QuestInstance q : KillGoal.flattenAll(root)) {
                                if (q.getInstanceId().equals(p.instanceId())) {
                                    qs.setStarred(q, !q.isStarred());
                                    return;
                                }
                            }
                        }
                    }));
        });

        // ===== 8. 任务同步管理器（M3 迁移：服务端 tick 推送整树 + 进度通道）=====
        QuestSyncManager.getInstance();

        // ===== 9. 客户端：按键 + HUD（M3 迁移；显式 addListener——包不能用 @EventBusSubscriber，A7）=====
        context.getModEventBus().addListener(RegisterKeyMappingsEvent.class,
                e -> e.register(QuestKeyBindings.getOpenQuest()));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> QuestKeyBindings.onClientTick());
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post e) -> QuestHUD.onRenderGui(e));

        LogManager.serverInfo(TAG, "QuestTestPack registered");
    }

    /** 示例 giver：玩家靠近指定坐标 → 发放 */
    private static class StartGiver implements IQuestGiver {
        private final Vec3 start;
        private final float radius;

        StartGiver(Vec3 start, float radius) {
            this.start = start;
            this.radius = radius;
        }

        @Override
        public boolean canGive(ServerPlayer player) {
            return player.position().distanceToSqr(start) <= (double) radius * radius;
        }
    }
}
