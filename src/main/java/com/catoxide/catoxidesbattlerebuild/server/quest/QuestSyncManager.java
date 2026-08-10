package com.catoxide.catoxidesbattlerebuild.server.quest;

import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import com.catoxide.catoxidesbattlerebuild.network.QuestSyncPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务状态同步管理器（服务端 → 客户端）。
 * <p>每 20 tick 对每个在线玩家推送完整任务树（平铺节点列表）。
 * <p>懒单例：必须在主类构造主动 {@link #getInstance()} 初始化（同 ServerBoneSyncManager）。
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
        for (QuestInstance quest : questSystem.getPlayerQuests(player.getUUID())) {
            collect(quest, null, entries);
        }
        if (!entries.isEmpty()) {
            new QuestSyncPacket(entries).send(player.connection::send);
        }
    }

    /** 递归平铺任务树（parentId 关联父子） */
    private static void collect(QuestInstance quest, String parentId, List<QuestSyncPacket.QuestEntry> out) {
        out.add(new QuestSyncPacket.QuestEntry(
                quest.getDefinition().id().toString(),
                quest.getDefinition().title().getString(),
                quest.getState().name(),
                quest.isStarred(),
                parentId));
        for (QuestInstance child : quest.getChildInstances()) {
            collect(child, quest.getDefinition().id().toString(), out);
        }
    }
}
