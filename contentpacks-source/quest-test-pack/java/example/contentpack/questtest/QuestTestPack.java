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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
        LogManager.serverInfo(TAG, "Registered 'proximity' goal + 'proximity_giver' giver factories");

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
                // 进度驱动（updateProgress 内部触发 checkCompletion → 完成/失败）
                for (QuestInstance q : questSystem.getActiveQuestsSorted(uid, player.position())) {
                    questSystem.updateProgress(q, "tick", 0f);
                }
            }
        });

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
