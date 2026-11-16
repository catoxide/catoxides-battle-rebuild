package example.contentpack.tech;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 科技树存档（存档级）。
 *
 * <p>进度按<b>研究台</b>存储（{@link TechTableKey} = 维度 + 坐标）：已解锁节点、研究点余额、
 * 解锁时刻（可重置解锁）、研究项上次提交时刻。触发器与已完成任务属于玩家知识而非台子进度，
 * 仍按玩家 UUID 存储。
 */
public final class TechSavedData extends SavedData {

    private static final String DATA_NAME = "tech_tree_pack";

    private final Map<TechTableKey, Set<String>> unlocked = new HashMap<>();
    private final Map<TechTableKey, Integer> points = new HashMap<>();
    private final Map<TechTableKey, Map<String, Long>> unlockTimes = new HashMap<>();
    private final Map<TechTableKey, Map<String, Long>> researchTimes = new HashMap<>();

    private final Map<UUID, Set<String>> triggers = new HashMap<>();
    private final Map<UUID, Set<String>> completedQuests = new HashMap<>();

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

    public Set<String> unlocked(TechTableKey table) {
        return unlocked.getOrDefault(table, Set.of());
    }

    public boolean isUnlocked(TechTableKey table, String nodeId) {
        return unlocked(table).contains(nodeId);
    }

    public boolean unlock(TechTableKey table, String nodeId) {
        boolean added = unlocked
                .computeIfAbsent(table, key -> new LinkedHashSet<>())
                .add(nodeId);
        if (added) {
            setDirty();
        }
        return added;
    }

    /** 强制回退为未解锁（可重置解锁 / 指令）。 */
    public boolean lock(TechTableKey table, String nodeId) {
        Set<String> set = unlocked.get(table);
        if (set != null && set.remove(nodeId)) {
            if (set.isEmpty()) {
                unlocked.remove(table);
            }
            setDirty();
            return true;
        }
        return false;
    }

    public long unlockTime(TechTableKey table, String nodeId) {
        Map<String, Long> times = unlockTimes.get(table);
        return times == null ? 0L : times.getOrDefault(nodeId, 0L);
    }

    public void recordUnlockTime(TechTableKey table, String nodeId, long millis) {
        unlockTimes.computeIfAbsent(table, key -> new HashMap<>()).put(nodeId, millis);
        setDirty();
    }

    // ==================== 科技点 ====================

    public int points(TechTableKey table) {
        return points.getOrDefault(table, 0);
    }

    public int addPoints(TechTableKey table, int amount) {
        if (amount == 0) {
            return points(table);
        }
        return setPoints(table, Math.max(0, points(table) + amount));
    }

    public int setPoints(TechTableKey table, int value) {
        int updated = Math.max(0, value);
        if (updated == 0) {
            points.remove(table);
        } else {
            points.put(table, updated);
        }
        setDirty();
        return updated;
    }

    /** 扣点；余额不足时不扣并返回 false。 */
    public boolean spendPoints(TechTableKey table, int amount) {
        int current = points(table);
        if (amount <= 0) {
            return true;
        }
        if (current < amount) {
            return false;
        }
        setPoints(table, current - amount);
        return true;
    }

    // ==================== 触发器 / 任务完成（玩家知识） ====================

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

    public long researchTime(TechTableKey table, String researchId) {
        Map<String, Long> times = researchTimes.get(table);
        return times == null ? 0L : times.getOrDefault(researchId, 0L);
    }

    public void recordResearchTime(TechTableKey table, String researchId, long millis) {
        researchTimes.computeIfAbsent(table, key -> new HashMap<>()).put(researchId, millis);
        setDirty();
    }

    // ==================== 序列化 ====================

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag tables = new ListTag();
        Set<TechTableKey> tableKeys = new LinkedHashSet<>(unlocked.keySet());
        tableKeys.addAll(points.keySet());
        tableKeys.addAll(unlockTimes.keySet());
        tableKeys.addAll(researchTimes.keySet());
        for (TechTableKey table : tableKeys) {
            if (unlocked(table).isEmpty()
                    && points(table) == 0
                    && unlockTimes.getOrDefault(table, Map.of()).isEmpty()
                    && researchTimes.getOrDefault(table, Map.of()).isEmpty()) {
                continue;
            }
            CompoundTag tableTag = writeTableKey(table);
            if (!unlocked(table).isEmpty()) {
                tableTag.put("nodes", stringList(unlocked(table)));
            }
            if (points(table) != 0) {
                tableTag.putInt("points", points(table));
            }
            CompoundTag unlockTag = new CompoundTag();
            for (Map.Entry<String, Long> entry : unlockTimes.getOrDefault(table, Map.of()).entrySet()) {
                unlockTag.putLong(entry.getKey(), entry.getValue());
            }
            if (!unlockTag.isEmpty()) {
                tableTag.put("unlock_times", unlockTag);
            }
            CompoundTag researchTag = new CompoundTag();
            for (Map.Entry<String, Long> entry : researchTimes.getOrDefault(table, Map.of()).entrySet()) {
                researchTag.putLong(entry.getKey(), entry.getValue());
            }
            if (!researchTag.isEmpty()) {
                tableTag.put("research_times", researchTag);
            }
            tables.add(tableTag);
        }
        tag.put("tables", tables);

        ListTag players = new ListTag();
        Set<UUID> playerIds = new LinkedHashSet<>(triggers.keySet());
        playerIds.addAll(completedQuests.keySet());
        for (UUID playerId : playerIds) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("player", playerId);
            if (!triggers(playerId).isEmpty()) {
                playerTag.put("triggers", stringList(triggers(playerId)));
            }
            if (!completedQuests(playerId).isEmpty()) {
                playerTag.put("quests", stringList(completedQuests(playerId)));
            }
            players.add(playerTag);
        }
        tag.put("players", players);
        return tag;
    }

    public static TechSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TechSavedData data = new TechSavedData();

        ListTag tables = tag.getList("tables", Tag.TAG_COMPOUND);
        for (int i = 0; i < tables.size(); i++) {
            CompoundTag tableTag = tables.getCompound(i);
            TechTableKey table = readTableKey(tableTag);
            if (table == null) {
                continue;
            }
            Set<String> nodes = readStrings(tableTag, "nodes");
            if (!nodes.isEmpty()) {
                data.unlocked.put(table, nodes);
            }
            int balance = tableTag.getInt("points");
            if (balance != 0) {
                data.points.put(table, balance);
            }
            CompoundTag unlockTag = tableTag.getCompound("unlock_times");
            if (!unlockTag.isEmpty()) {
                Map<String, Long> times = new HashMap<>();
                for (String key : unlockTag.getAllKeys()) {
                    times.put(key, unlockTag.getLong(key));
                }
                data.unlockTimes.put(table, times);
            }
            CompoundTag researchTag = tableTag.getCompound("research_times");
            if (!researchTag.isEmpty()) {
                Map<String, Long> times = new HashMap<>();
                for (String key : researchTag.getAllKeys()) {
                    times.put(key, researchTag.getLong(key));
                }
                data.researchTimes.put(table, times);
            }
        }

        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            UUID playerId = playerTag.getUUID("player");
            Set<String> triggerSet = readStrings(playerTag, "triggers");
            if (!triggerSet.isEmpty()) {
                data.triggers.put(playerId, triggerSet);
            }
            Set<String> questSet = readStrings(playerTag, "quests");
            if (!questSet.isEmpty()) {
                data.completedQuests.put(playerId, questSet);
            }
        }
        return data;
    }

    private static CompoundTag writeTableKey(TechTableKey table) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", table.dimension().toString());
        tag.putInt("x", table.pos().getX());
        tag.putInt("y", table.pos().getY());
        tag.putInt("z", table.pos().getZ());
        return tag;
    }

    private static TechTableKey readTableKey(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dim"));
        if (dimension == null) {
            return null;
        }
        return new TechTableKey(dimension,
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
    }

    private static ListTag stringList(Set<String> values) {
        ListTag list = new ListTag();
        for (String value : values) {
            list.add(StringTag.valueOf(value));
        }
        return list;
    }

    private static Set<String> readStrings(CompoundTag tag, String key) {
        Set<String> out = new LinkedHashSet<>();
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getString(i));
        }
        return out;
    }
}
