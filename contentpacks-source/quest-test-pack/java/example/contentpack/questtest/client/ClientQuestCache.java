package example.contentpack.questtest.client;

import example.contentpack.questtest.network.QuestSyncPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端任务缓存（接收服务端 QuestSyncPacket 后更新）。
 * <p>HUD/UI 统一从此读数据（单机/多人一致，不直接碰服务端 QuestSystem）。
 * <p><b>M3 迁移</b>：由 core 移入本包。
 */
public final class ClientQuestCache {

    private static final ClientQuestCache INSTANCE = new ClientQuestCache();

    private final List<QuestSyncPacket.QuestEntry> entries = new ArrayList<>();
    private UUID playerId;

    private ClientQuestCache() {
    }

    public static ClientQuestCache getInstance() {
        return INSTANCE;
    }

    public synchronized void update(UUID playerId, List<QuestSyncPacket.QuestEntry> entries) {
        this.playerId = playerId;
        this.entries.clear();
        this.entries.addAll(entries);
    }

    /** 当前玩家全部任务节点（平铺） */
    public synchronized List<QuestSyncPacket.QuestEntry> getEntries() {
        return List.copyOf(entries);
    }

    /** 根任务（parentInstanceId 为空串） */
    public synchronized List<QuestSyncPacket.QuestEntry> getRootQuests() {
        return entries.stream()
                .filter(e -> e.parentInstanceId() == null || e.parentInstanceId().isEmpty())
                .toList();
    }

    /** 子任务（parentInstanceId = 父实例 ID） */
    public synchronized List<QuestSyncPacket.QuestEntry> getChildren(String parentInstanceId) {
        return entries.stream().filter(e -> parentInstanceId.equals(e.parentInstanceId())).toList();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    /** 本地预览星标切换（服务端确认后同步包会覆盖校正） */
    public synchronized void applyStar(String instanceId, boolean starred) {
        for (int i = 0; i < entries.size(); i++) {
            QuestSyncPacket.QuestEntry e = entries.get(i);
            if (e.instanceId().equals(instanceId)) {
                entries.set(i, new QuestSyncPacket.QuestEntry(
                        e.instanceId(), e.definitionId(), e.title(), e.description(),
                        e.state(), starred, e.parentInstanceId(), e.location(), e.progress()));
                return;
            }
        }
    }

    /** 进度显示格式："killed=2 need=2"（整数值去掉 .0；空 map 返回空串） */
    public static String formatProgress(Map<String, Float> progress) {
        if (progress == null || progress.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var e : progress.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.getKey()).append('=').append(fmt(e.getValue()));
        }
        return sb.toString();
    }

    private static String fmt(float v) {
        return v == Math.floor(v) && !Float.isInfinite(v)
                ? String.valueOf((long) v)
                : String.valueOf(v);
    }
}
