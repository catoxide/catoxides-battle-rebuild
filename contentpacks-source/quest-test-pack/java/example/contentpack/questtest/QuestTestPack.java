package example.contentpack.questtest;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestDefinition;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestEvents;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import com.catoxide.catoxidesbattlerebuild.core.quest.IQuestGiver;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.UUID;

/**
 * 测试内容包：验证任务基座（QuestSystem）完整链路。
 * <p>验证目标：
 * <ul>
 *   <li>registerQuest 定义注册 + 任务树（主任务容器 + 2 子任务）</li>
 *   <li>registerGoalType 工厂注册（"proximity" JSON → ProximityGoal）</li>
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

        // ===== 1. 注册 goal 工厂（"proximity" JSON → ProximityGoal）=====
        questSystem.registerGoalType("proximity", json -> {
            JsonArray t = json.getAsJsonArray("target");
            Vec3 target = new Vec3(t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.get(2).getAsDouble());
            float radius = json.has("radius") ? json.get("radius").getAsFloat() : 5f;
            return new ProximityGoal(target, radius);
        });
        LogManager.serverInfo(TAG, "Registered 'proximity' goal factory");

        // ===== 2. 任务树：main（容器） + sub1 + sub2 =====
        Vec3 start = new Vec3(0, 64, 0);      // 起始点（发放触发区）
        Vec3 pointA = new Vec3(100, 64, 100); // 子任务 1 目标
        Vec3 pointB = new Vec3(200, 64, 200); // 子任务 2 目标

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
                List.of(),               // 容器任务：无自身 goal
                List.of(), List.of(),
                List.of(sub1, sub2),     // 子任务树
                null, 0f);

        context.registerQuest(main);
        ResourceLocation mainId = main.id();
        LogManager.serverInfo(TAG, "Registered quest tree: main={}, children={}",
                mainId, main.children().size());

        // ===== 3. 事件日志（验证事件广播）=====
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

        // ===== 4. tick 驱动：发放检查 + 进度更新 =====
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            var server = e.getServer();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                UUID uid = player.getUUID();
                // 发放：无激活 test_main 且 giver 条件满足 → 发放
                boolean hasMain = questSystem.getPlayerQuests(uid).stream()
                        .anyMatch(q -> q.getDefinition().id().equals(mainId) && q.isActive());
                if (!hasMain) {
                    QuestDefinition def = questSystem.getDefinition(mainId);
                    if (def != null && def.givers().stream().anyMatch(g -> g.canGive(player))) {
                        questSystem.giveQuest(player, mainId, "start_giver");
                    }
                }
                // 进度驱动：更新距离并触发完成检查（updateProgress 内部调 checkCompletion）
                for (QuestInstance q : questSystem.getActiveQuestsSorted(uid, player.position())) {
                    questSystem.updateProgress(q, "tick", 0f);
                }
            }
        });

        LogManager.serverInfo(TAG, "QuestTestPack registered");
    }

    /** 示例 giver：玩家靠近起始点 → 发放 */
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
