package example.contentpack.tech.quest;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestDefinition;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.tech.TechNode;
import example.contentpack.tech.TechService;
import example.contentpack.tech.TechTree;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 科技节点 ↔ 个人任务。
 * <p>每个 {@code "quest": true} 的节点注册一条一次性任务：条件满足即发放，
 * 解锁该节点即完成。任务 id 为 {@code <pack>:tech_<nodeId>}。
 * <p>可重复提交由科技树界面上的按钮直接结算（{@code TechService.submitResearch}），
 * 不再生成自动完成的提交任务。
 */
public final class TechQuestBridge {

    private static final String TAG = "TechTreeQuest";

    private TechQuestBridge() {
    }

    /** 注册 JSON 定义可用的工厂（goal/giver 的 type 与 Java 侧一致）。 */
    public static void registerFactories(QuestSystem questSystem) {
        questSystem.registerGoalType(TechNodeGoal.TYPE, json ->
                new TechNodeGoal(json.get("node").getAsString()));
        questSystem.registerGiverType(TechQuestGiver.TYPE, json -> {
            String nodeId = json.get("node").getAsString();
            TechNode node = TechService.tree().node(nodeId);
            if (node == null) {
                throw new IllegalArgumentException("unknown tech node '" + nodeId + "'");
            }
            return new TechQuestGiver(node);
        });
    }

    /** 注册节点任务（一次性、不发科技点；科技点只由任务奖励与按钮提交产出）。 */
    public static void registerQuests(ContentPackContext context, TechTree tree) {
        int nodeQuests = 0;
        for (TechNode node : tree.nodes()) {
            if (!node.quest()) {
                continue;
            }
            ResourceLocation questId = context.id("tech_" + node.id());
            QuestDefinition definition = new QuestDefinition(
                    questId,
                    Component.literal(node.title()),
                    Component.literal(node.desc()),
                    List.of(new TechQuestGiver(node)),
                    List.of(new TechNodeGoal(node.id())),
                    List.of(), List.of(), List.of(),
                    null, 0f);
            context.registerQuest(definition);
            // 解锁节点本身不是「完成任务换点」，避免解锁时反手退还科技点
            TechService.setQuestReward(questId, 0);
            nodeQuests++;
        }
        LogManager.serverInfo(TAG, "Registered {} tech quest(s) (nodes={})",
                nodeQuests, nodeQuests);
    }
}
