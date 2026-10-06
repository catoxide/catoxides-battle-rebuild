package example.contentpack.questtest.server;

import com.catoxide.catoxidesbattlerebuild.core.quest.IQuestGoal;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import example.contentpack.questtest.network.QuestSyncPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务状态同步管理器（服务端 → 客户端）。
 * <p>每 20 tick 对每个在线玩家推送完整任务树（平铺节点列表）。
 * <p><b>M3 迁移</b>：由 core 移入本包。必须随包迁移的原因：它直接
 * {@code new QuestSyncPacket(...)}——包类留在 core 会构成 core→包引用（规范 §4.4 规则 B）。
 * 懒单例：由 {@code QuestTestPack.register()} 主动 {@link #getInstance()} 初始化。
 */
public final class QuestSyncManager {

    private static final QuestSyncManager INSTANCE = new QuestSyncManager();
    private static final int SYNC_INTERVAL = 20;

    private int tickCounter = 0;

    private QuestSyncManager() {
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    public static QuestSyncManager getInstance() {
        return INSTANCE;
    }

    @SubscribeEvent
    private void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < SYNC_INTERVAL) {
            return;
        }
        tickCounter = 0;
        var server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncToPlayer(player);
        }
    }

    private void syncToPlayer(ServerPlayer player) {
        QuestSystem questSystem = QuestSystem.getInstance();
        List<QuestSyncPacket.QuestEntry> entries = new ArrayList<>();
        // 按 UI 优先级排序收集根任务（星标>priority>距离>时间）——客户端 HUD top3 直接取前 N
        for (QuestInstance quest : questSystem.getActiveQuestsSorted(player.getUUID(), player.position())) {
            collect(quest, player, null, entries);
        }
        // 已完成/失败的也收集（O 菜单"已完成"分区需要）
        for (QuestInstance quest : questSystem.getPlayerQuests(player.getUUID())) {
            if (quest.isActive()) {
                continue;   // 已在上方收集
            }
            collect(quest, player, null, entries);
        }
        // 无条件发送（含空列表）：任务全消除后客户端缓存必须清空，否则 HUD 残留旧任务
        new QuestSyncPacket(entries).send(player.connection::send);
    }

    /** 递归平铺任务树（instanceId 关联父子；根任务 parentInstanceId 用空串——STRING_UTF8 不允许 null） */
    private static void collect(QuestInstance quest, ServerPlayer player, String parentInstanceId,
                                List<QuestSyncPacket.QuestEntry> out) {
        out.add(new QuestSyncPacket.QuestEntry(
                quest.getInstanceId(),
                quest.getDefinition().id().toString(),
                quest.getDefinition().title().getString(),
                quest.getDefinition().description().getString(),
                quest.getState().name(),
                quest.isStarred(),
                parentInstanceId == null ? "" : parentInstanceId,
                formatLocation(quest),
                collectProgress(quest, player)));
        for (QuestInstance child : quest.getChildInstances()) {
            collect(child, player, quest.getInstanceId(), out);
        }
    }

    /**
     * 进度通道：合并该任务全部 goal 的 {@link IQuestGoal#getProgress}（此前 core 里零调用者，
     * UI 做不出 "3/5"）。goal 抛异常不阻断同步（单个 goal 的显示问题不该拖垮整棵树）。
     */
    private static Map<String, Float> collectProgress(QuestInstance quest, ServerPlayer player) {
        Map<String, Float> out = new HashMap<>();
        for (IQuestGoal goal : quest.getDefinition().goals()) {
            try {
                Map<String, Float> p = goal.getProgress(quest, player);
                if (p != null) {
                    out.putAll(p);
                }
            } catch (Exception ignored) {
                // 单个 goal 的进度展示失败不影响同步
            }
        }
        return out;
    }

    /** 任务目标位置 → "x,y,z"（无则空串） */
    private static String formatLocation(QuestInstance quest) {
        net.minecraft.world.phys.Vec3 loc = quest.getDefinition().location();
        if (loc == null) {
            return "";
        }
        return String.format("%.0f,%.0f,%.0f", loc.x, loc.y, loc.z);
    }
}
