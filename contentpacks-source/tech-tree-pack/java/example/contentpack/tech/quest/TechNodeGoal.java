package example.contentpack.tech.quest;

import com.catoxide.catoxidesbattlerebuild.core.quest.IQuestGoal;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import example.contentpack.tech.TechService;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** 任务目标：该玩家已解锁指定科技节点（`isComplete` 只读，无副作用）。 */
public final class TechNodeGoal implements IQuestGoal {

    public static final String TYPE = "tech_node";

    private final String nodeId;

    public TechNodeGoal(String nodeId) {
        this.nodeId = nodeId;
    }

    public String nodeId() {
        return nodeId;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public boolean isComplete(QuestInstance quest, ServerPlayer player) {
        return TechService.isUnlocked(player, nodeId);
    }

    @Override
    public Map<String, Float> getProgress(QuestInstance quest, ServerPlayer player) {
        return Map.of("unlocked", TechService.isUnlocked(player, nodeId) ? 1f : 0f);
    }
}
