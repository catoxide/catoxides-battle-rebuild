package example.contentpack.tech.quest;

import com.catoxide.catoxidesbattlerebuild.core.quest.IQuestGiver;
import example.contentpack.tech.TechNode;
import example.contentpack.tech.TechRequirementEngine;
import example.contentpack.tech.TechService;
import net.minecraft.server.level.ServerPlayer;

/**
 * 发放源：节点可见、解锁条件已满足且尚未解锁时，把「研究该科技」作为个人任务发给玩家。
 * <p>条件用与解锁完全同一套引擎判定（{@code TechService.contextOf} 取玩家最近使用的台子）。
 * 已解锁 ⇒ 不发；非一次性设置由 {@code QuestGivePolicy} 兜底（不会重复给）。
 */
public final class TechQuestGiver implements IQuestGiver {

    public static final String TYPE = "tech_available";

    private final TechNode node;

    public TechQuestGiver(TechNode node) {
        this.node = node;
    }

    public TechNode node() {
        return node;
    }

    @Override
    public boolean canGive(ServerPlayer player) {
        if (TechService.isUnlocked(player, node.id())) {
            return false;
        }
        TechRequirementEngine.Context context = TechService.contextOf(player);
        return TechService.requirementsMet(player, node.visibleWhen(), context)
                && TechService.requirementsMet(player, node.requirements(), context);
    }
}
