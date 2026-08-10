package com.catoxide.catoxidesbattlerebuild.client.quest;

import com.catoxide.catoxidesbattlerebuild.network.QuestSyncPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 客户端任务缓存（接收服务端 QuestSyncPacket 后更新）。
 * <p>HUD/UI 统一从此读数据（单机/多人一致，不直接碰服务端 QuestSystem）。
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

    /** 根任务（parentId 为 null） */
    public synchronized List<QuestSyncPacket.QuestEntry> getRootQuests() {
        return entries.stream().filter(e -> e.parentId() == null).toList();
    }

    /** 子任务（parentId = 父 id） */
    public synchronized List<QuestSyncPacket.QuestEntry> getChildren(String parentId) {
        return entries.stream().filter(e -> parentId.equals(e.parentId())).toList();
    }

    public UUID getPlayerId() {
        return playerId;
    }
}
