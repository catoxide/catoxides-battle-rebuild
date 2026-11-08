package example.contentpack.tech;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 科技进度持久化（存档级，按玩家 UUID）：已解锁节点、科技点余额、
 * 自定义触发器、已完成任务、解锁时刻（可重置解锁）、研究项上次提交时刻。
 */
public final class TechSavedData extends SavedData {

    private static final String DATA_NAME = "tech_tree_pack";

    private final Map<UUID, Set<String>> unlocked = new HashMap<>();
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, Set<String>> triggers = new HashMap<>();
    private final Map<UUID, Set<String>> completedQuests = new HashMap<>();
    private final Map<UUID, Map<String, Long>> unlockTimes = new HashMap<>();
    private final Map<UUID, Map<String, Long>> researchTimes = new HashMap<>();

    private TechSavedData() {
    }

    public static TechSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        TechSavedData::new,
                        TechSavedData::load,
                        DataFixTypes.SAVED_DATA_MAP_DATA),
                DATA_NAME);
    }

    // ==================== 已解锁节点 ====================

    public Set<String> unlocked(UUID playerId) {
        return unlocked.getOrDefault(playerId, Set.of());
    }

    public boolean isUnlocked(UUID playerId, String nodeId) {
        return unlocked(playerId).contains(nodeId);
    }

    public boolean unlock(UUID playerId, String nodeId) {
        boolean added = unlocked
                .computeIfAbsent(playerId, key -> new LinkedHashSet<>())
                .add(nodeId);
        if (added) {
            setDirty();
        }
        return added;
    }

    /** 强制回退为未解锁（可重置解锁 / 指令）。 */
    public boolean lock(UUID playerId, String nodeId) {
        Set<String> set = unlocked.get(playerId);
        if (set != null && set.remove(nodeId)) {
            if (set.isEmpty()) {
                unlocked.remove(playerId);
            }
            setDirty();
            return true;
        }
        return false;
    }

    public long unlockTime(UUID playerId, String nodeId) {
        Map<String, Long> times = unlockTimes.get(playerId);
        return times == null ? 0L : times.getOrDefault(nodeId, 0L);
    }

    public void recordUnlockTime(UUID playerId, String nodeId, long millis) {
        unlockTimes.computeIfAbsent(playerId, key -> new HashMap<>()).put(nodeId, millis);
        setDirty();
    }

    // ==================== 科技点 ====================

    public int points(UUID playerId) {
        return points.getOrDefault(playerId, 0);
    }

    public int addPoints(UUID playerId, int amount) {
        if (amount == 0) {
            return points(playerId);
        }
        return setPoints(playerId, Math.max(0, points(playerId) + amount));
    }

    public int setPoints(UUID playerId, int value) {
        int updated = Math.max(0, value);
        if (updated == 0) {
            points.remove(playerId);
        } else {
            points.put(playerId, updated);
        }
        setDirty();
        return updated;
    }

    /** 扣点；余额不足时不扣并返回 false。 */
    public boolean spendPoints(UUID playerId, int amount) {
        int current = points(playerId);
        if (amount <= 0) {
            return true;
        }
        if (current < amount) {
            return false;
        }
        setPoints(playerId, current - amount);
        return true;
    }

    // ==================== 触发器 / 任务完成 ====================

    public Set<String> triggers(UUID playerId) {
        return triggers.getOrDefault(playerId, Set.of());
    }

    public boolean hasTrigger(UUID playerId, String trigger) {
        return triggers(playerId).contains(trigger);
    }

    public void setTrigger(UUID playerId, String trigger, boolean value) {
        Set<String> set = triggers.computeIfAbsent(playerId, key -> new LinkedHashSet<>());
        boolean changed = value ? set.add(trigger) : set.remove(trigger);
        if (set.isEmpty()) {
            triggers.remove(playerId);
        }
        if (changed) {
            setDirty();
        }
    }

    public Set<String> completedQuests(UUID playerId) {
        return completedQuests.getOrDefault(playerId, Set.of());
    }

    public void addCompletedQuest(UUID playerId, String questId) {
        if (completedQuests.computeIfAbsent(playerId, key -> new LinkedHashSet<>()).add(questId)) {
            setDirty();
        }
    }

    // ==================== 研究项提交时刻 ====================

    public long researchTime(UUID playerId, String researchId) {
        Map<String, Long> times = researchTimes.get(playerId);
        return times == null ? 0L : times.getOrDefault(researchId, 0L);
    }

    public void recordResearchTime(UUID playerId, String researchId, long millis) {
        researchTimes.computeIfAbsent(playerId, key -> new HashMap<>()).put(researchId, millis);
        setDirty();
    }

    // ==================== 序列化 ====================

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        Set<UUID> playerIds = new LinkedHashSet<>(unlocked.keySet());
        playerIds.addAll(points.keySet());
        playerIds.addAll(triggers.keySet());
        playerIds.addAll(completedQuests.keySet());
        playerIds.addAll(unlockTimes.keySet());
        playerIds.addAll(researchTimes.keySet());
        for (UUID playerId : playerIds) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("player", playerId);

            if (!unlocked(playerId).isEmpty()) {
                ListTag nodes = new ListTag();
                for (String node : unlocked(playerId)) {
                    nodes.add(StringTag.valueOf(node));
                }
                playerTag.put("nodes", nodes);
            }
            if (points(playerId) != 0) {
                playerTag.putInt("points", points(playerId));
            }
            if (!triggers(playerId).isEmpty()) {
                ListTag list = new ListTag();
                for (String trigger : triggers(playerId)) {
                    list.add(StringTag.valueOf(trigger));
                }
                playerTag.put("triggers", list);
            }
            if (!completedQuests(playerId).isEmpty()) {
                ListTag list = new ListTag();
                for (String quest : completedQuests(playerId)) {
                    list.add(StringTag.valueOf(quest));
                }
                playerTag.put("quests", list);
            }
            CompoundTag unlockTag = new CompoundTag();
            for (Map.Entry<String, Long> entry : unlockTimes.getOrDefault(playerId, Map.of()).entrySet()) {
                unlockTag.putLong(entry.getKey(), entry.getValue());
            }
            if (!unlockTag.isEmpty()) {
                playerTag.put("unlock_times", unlockTag);
            }
            CompoundTag researchTag = new CompoundTag();
            for (Map.Entry<String, Long> entry : researchTimes.getOrDefault(playerId, Map.of()).entrySet()) {
                researchTag.putLong(entry.getKey(), entry.getValue());
            }
            if (!researchTag.isEmpty()) {
                playerTag.put("research_times", researchTag);
            }
            players.add(playerTag);
        }
        tag.put("players", players);
        return tag;
    }

    public static TechSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TechSavedData data = new TechSavedData();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            UUID playerId = playerTag.getUUID("player");

            Set<String> nodesSet = new LinkedHashSet<>();
            ListTag nodes = playerTag.getList("nodes", Tag.TAG_STRING);
            for (int j = 0; j < nodes.size(); j++) {
                nodesSet.add(nodes.getString(j));
            }
            if (!nodesSet.isEmpty()) {
                data.unlocked.put(playerId, nodesSet);
            }
            int balance = playerTag.getInt("points");
            if (balance != 0) {
                data.points.put(playerId, balance);
            }
            Set<String> triggerSet = new LinkedHashSet<>();
            ListTag triggerList = playerTag.getList("triggers", Tag.TAG_STRING);
            for (int j = 0; j < triggerList.size(); j++) {
                triggerSet.add(triggerList.getString(j));
            }
            if (!triggerSet.isEmpty()) {
                data.triggers.put(playerId, triggerSet);
            }
            Set<String> questSet = new LinkedHashSet<>();
            ListTag questList = playerTag.getList("quests", Tag.TAG_STRING);
            for (int j = 0; j < questList.size(); j++) {
                questSet.add(questList.getString(j));
            }
            if (!questSet.isEmpty()) {
                data.completedQuests.put(playerId, questSet);
            }
            CompoundTag unlockTag = playerTag.getCompound("unlock_times");
            if (!unlockTag.isEmpty()) {
                Map<String, Long> times = new HashMap<>();
                for (String key : unlockTag.getAllKeys()) {
                    times.put(key, unlockTag.getLong(key));
                }
                data.unlockTimes.put(playerId, times);
            }
            CompoundTag researchTag = playerTag.getCompound("research_times");
            if (!researchTag.isEmpty()) {
                Map<String, Long> times = new HashMap<>();
                for (String key : researchTag.getAllKeys()) {
                    times.put(key, researchTag.getLong(key));
                }
                data.researchTimes.put(playerId, times);
            }
        }
        return data;
    }
}
