package example.contentpack.questtest;

import com.catoxide.catoxidesbattlerebuild.core.quest.IQuestGoal;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import com.catoxide.catoxidesbattlerebuild.core.quest.event.QuestEventBridge;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

import java.util.HashMap;
import java.util.Map;

/**
 * 示例目标：合成指定物品 N 次（验证事件桥 ItemCraftedEvent 链路）。
 * <p>监听 {@link QuestEventBridge.ItemCraftedEvent}，匹配物品后推进激活实例进度。
 */
public class CraftGoal implements IQuestGoal {

    private final String itemId;   // 目标物品（如 "minecraft:stick"）
    private final float count;     // 需合成数

    public CraftGoal(String itemId, float count) {
        this.itemId = itemId;
        this.count = count;
        NeoForge.EVENT_BUS.addListener(this::onItemCrafted);
    }

    private void onItemCrafted(QuestEventBridge.ItemCraftedEvent event) {
        String resultId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(event.result.getItem()).toString();
        if (!resultId.equals(itemId)) {
            return;
        }
        ServerPlayer player = event.player;
        for (QuestInstance root : QuestSystem.getInstance().getPlayerQuests(player.getUUID())) {
            for (QuestInstance inst : KillGoal.flattenAll(root)) {
                if (inst.isActive() && inst.getDefinition().goals().contains(this)) {
                    float current = inst.getGoalProgress("crafted");
                    QuestSystem.getInstance().updateProgress(inst, "crafted", current + 1);
                }
            }
        }
    }

    @Override
    public String getType() {
        return "craft";
    }

    @Override
    public boolean isComplete(QuestInstance quest, ServerPlayer player) {
        return quest.getGoalProgress("crafted") >= count;
    }

    @Override
    public Map<String, Float> getProgress(QuestInstance quest, ServerPlayer player) {
        Map<String, Float> progress = new HashMap<>();
        progress.put("crafted", quest.getGoalProgress("crafted"));
        progress.put("need", count);
        return progress;
    }
}
