package com.catoxide.catoxidesbattlerebuild.server.quest;

import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务系统持久化（世界存档）。
 * <p>保存每个玩家的任务树（根 + 子任务递归：定义id/状态/进度/星标/发放信息）。
 * <p>世界加载时经 {@link #load} 解析并调 {@link QuestSystem#restoreAll} 恢复。
 */
public class QuestSavedData extends SavedData {

    private static final String TAG = "QuestSavedData";
    private static final String DATA_NAME = "catoxidesbattlerebuild_quests";

    private QuestSavedData() {
    }

    public static QuestSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<QuestSavedData>(
                        QuestSavedData::new,
                        QuestSavedData::load,
                        net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_MAP_DATA),
                DATA_NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, List<QuestInstance>> entry
                : QuestSystem.getInstance().getAllPlayerRoots().entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("player", entry.getKey());
            ListTag quests = new ListTag();
            for (QuestInstance root : entry.getValue()) {
                quests.add(serializeQuest(root));
            }
            playerTag.put("quests", quests);
            players.add(playerTag);
        }
        tag.put("players", players);
        LogManager.serverInfo(TAG, "Saved %d players' quest trees", players.size());
        return tag;
    }

    public static QuestSavedData load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        QuestSavedData data = new QuestSavedData();
        Map<UUID, List<CompoundTag>> toRestore = new HashMap<>();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            UUID playerId = playerTag.getUUID("player");
            ListTag quests = playerTag.getList("quests", Tag.TAG_COMPOUND);
            List<CompoundTag> trees = new ArrayList<>();
            for (int j = 0; j < quests.size(); j++) {
                trees.add(quests.getCompound(j));
            }
            toRestore.put(playerId, trees);
        }
        QuestSystem.getInstance().restoreAll(toRestore);
        LogManager.serverInfo(TAG, "Loaded quest data for %d players", toRestore.size());
        return data;
    }

    // ========== 序列化 ==========

    static CompoundTag serializeQuest(QuestInstance quest) {
        CompoundTag t = new CompoundTag();
        t.putString("defId", quest.getDefinition().id().toString());
        t.putString("state", quest.getState().name());
        t.putBoolean("starred", quest.isStarred());
        t.putLong("startedAt", quest.getStartedAt());
        String giverId = quest.getGiverId();
        t.putString("giverId", giverId == null ? "" : giverId);
        CompoundTag progress = new CompoundTag();
        quest.getGoalProgress().forEach(progress::putFloat);
        t.put("progress", progress);
        ListTag children = new ListTag();
        for (QuestInstance child : quest.getChildInstances()) {
            children.add(serializeQuest(child));
        }
        t.put("children", children);
        return t;
    }
}
