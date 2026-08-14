package example.contentpack.questtest;

import com.catoxide.catoxidesbattlerebuild.core.quest.IQuestGoal;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import com.catoxide.catoxidesbattlerebuild.core.quest.event.QuestEventBridge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

import java.util.HashMap;
import java.util.Map;

/**
 * 示例目标：击杀指定生物类型 N 只（验证事件桥链路）。
 * <p>监听 {@link QuestEventBridge.MobKilledEvent}，对匹配类型的击杀
 * 推进该定义所有激活实例的进度（updateProgress → checkCompletion → 完成）。
 */
public class KillGoal implements IQuestGoal {

    private final String entityType;   // 目标生物类型（如 "minecraft:zombie"）
    private final float count;         // 需击杀数
    private final String progressKey;

    public KillGoal(String entityType, float count) {
        this.entityType = entityType;
        this.count = count;
        this.progressKey = "killed_" + entityType.replace(':', '_');
        // 定义级监听：事件来临时推进所有使用本 goal 的激活实例
        NeoForge.EVENT_BUS.addListener(this::onMobKilled);
    }

    private void onMobKilled(QuestEventBridge.MobKilledEvent event) {
        if (!matches(event.target)) {
            return;
        }
        ServerPlayer player = event.player;
        for (QuestInstance root : QuestSystem.getInstance().getPlayerQuests(player.getUUID())) {
            for (QuestInstance inst : flatten(root).toList()) {
                if (inst.isActive() && inst.getDefinition().goals().contains(this)) {
                    float current = inst.getGoalProgress(progressKey);
                    QuestSystem.getInstance().updateProgress(inst, progressKey, current + 1);
                }
            }
        }
    }

    private boolean matches(net.minecraft.world.entity.LivingEntity target) {
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        return key != null && key.toString().equals(entityType);
    }

    private static java.util.stream.Stream<QuestInstance> flatten(QuestInstance quest) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(quest),
                quest.getChildInstances().stream().flatMap(KillGoal::flatten));
    }

    /** 递归展开任务树（根 + 所有后代），供其他 goal 复用 */
    public static java.util.List<QuestInstance> flattenAll(QuestInstance quest) {
        return flatten(quest).toList();
    }

    @Override
    public String getType() {
        return "kill";
    }

    @Override
    public boolean isComplete(QuestInstance quest, ServerPlayer player) {
        return quest.getGoalProgress(progressKey) >= count;
    }

    @Override
    public Map<String, Float> getProgress(QuestInstance quest, ServerPlayer player) {
        Map<String, Float> progress = new HashMap<>();
        progress.put("killed", quest.getGoalProgress(progressKey));
        progress.put("need", count);
        return progress;
    }
}
