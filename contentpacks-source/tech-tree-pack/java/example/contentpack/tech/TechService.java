package example.contentpack.tech;

import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.tech.network.TechSyncPayload;
import example.contentpack.tech.network.TechTableSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 科技树服务端逻辑：条件判定、解锁、可重置、研究提交、升级研究台、同步客户端。 */
public final class TechService {

    private static final String TAG = "TechTree";
    private static final double TABLE_REACH_SQR = 8.0 * 8.0;
    private static final long MILLIS_PER_TICK = 50L;

    private static TechTree tree = TechTree.EMPTY;
    /** 任务 id → 完成发放的科技点（覆盖全局 {@code points.questReward}）。 */
    private static final Map<ResourceLocation, Integer> questRewards = new HashMap<>();
    /** 玩家最近一次使用的研究台（登录同步 / 任务发放时评估条件用）。 */
    private static final Map<UUID, TechRequirementEngine.Context> lastTable = new HashMap<>();

    private TechService() {
    }

    public static void install(TechTree loaded) {
        tree = loaded == null ? TechTree.EMPTY : loaded;
    }

    public static TechTree tree() {
        return tree;
    }

    // ==================== 研究台身份 ====================

    public static TechTableKey tableKey(ServerLevel level, BlockPos pos) {
        return pos == null ? null : TechTableKey.of(level, pos);
    }

    /** 玩家最近使用的台子；从未打开过研究台时为 null。 */
    public static TechTableKey boundTable(ServerPlayer player) {
        TechRequirementEngine.Context context = lastTable.get(player.getUUID());
        if (context == null || context.tablePos() == null) {
            return null;
        }
        ResourceLocation dimension = context.dimension() != null
                ? context.dimension()
                : player.serverLevel().dimension().location();
        return new TechTableKey(dimension, context.tablePos());
    }

    public static boolean hasBoundTable(ServerPlayer player) {
        return boundTable(player) != null;
    }

    /** 条件上下文对应的台子：优先上下文里的坐标，否则回落到玩家当前台子。 */
    public static TechTableKey key(ServerPlayer player, TechRequirementEngine.Context context) {
        if (context != null && context.tablePos() != null) {
            ResourceLocation dimension = context.dimension() != null
                    ? context.dimension()
                    : player.serverLevel().dimension().location();
            return new TechTableKey(dimension, context.tablePos());
        }
        return boundTable(player);
    }

    // ==================== 科技点 ====================

    /** 玩家当前台子的科技点余额；客户端（无服务端）返回 0，界面用同步下来的值。 */
    public static int points(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.getServer() == null) {
            return 0;
        }
        TechTableKey table = boundTable(serverPlayer);
        return table == null ? 0 : data(serverPlayer).points(table);
    }

    public static int points(ServerPlayer player, TechRequirementEngine.Context context) {
        if (player.getServer() == null) {
            return 0;
        }
        TechTableKey table = key(player, context);
        return table == null ? 0 : data(player).points(table);
    }

    public static int points(ServerPlayer player, TechTableKey table) {
        if (player.getServer() == null || table == null) {
            return 0;
        }
        return data(player).points(table);
    }

    /** 发放科技点到玩家当前台子并立即同步余额。返回新余额。 */
    public static int addPoints(ServerPlayer player, int amount) {
        if (player.getServer() == null || amount <= 0) {
            return points(player);
        }
        TechTableKey table = boundTable(player);
        if (table == null) {
            LogManager.serverWarn(TAG, "%s has no bound research table; %d tech point(s) not credited",
                    player.getName().getString(), amount);
            return 0;
        }
        int updated = data(player).addPoints(table, amount);
        sync(player);
        LogManager.serverInfo(TAG, "granted %d tech point(s) to table %s (balance=%d)",
                amount, table.pos().toShortString(), updated);
        return updated;
    }

    /** 机器/指令直接给指定台子加点（无需玩家打开界面）。 */
    public static int addPoints(ServerLevel level, BlockPos pos, int amount) {
        if (level == null || pos == null || amount <= 0) {
            return 0;
        }
        TechTableKey table = TechTableKey.of(level, pos);
        return TechSavedData.get(level.getServer().overworld()).addPoints(table, amount);
    }

    /** 直接设置玩家当前台子的余额（指令/调试）。 */
    public static int setPoints(ServerPlayer player, int value) {
        TechTableKey table = boundTable(player);
        if (table == null) {
            return 0;
        }
        int updated = data(player).setPoints(table, value);
        sync(player);
        return updated;
    }

    /** 从玩家当前台子扣点（调用方已确保余额足够）。 */
    public static boolean spendPoints(ServerPlayer player, int amount) {
        TechTableKey table = boundTable(player);
        return table != null && data(player).spendPoints(table, amount);
    }

    public static boolean spendPoints(ServerPlayer player, TechTableKey table, int amount) {
        return player.getServer() != null && table != null
                && data(player).spendPoints(table, amount);
    }

    public static void setQuestReward(ResourceLocation questId, int points) {
        questRewards.put(questId, points);
    }

    /** 任务完成时发放的科技点：专属配置优先，否则用全局 {@code points.questReward}。 */
    public static int questPointReward(ResourceLocation questId) {
        Integer override = questRewards.get(questId);
        if (override != null) {
            return override;
        }
        return tree.points().questReward();
    }

    // ==================== 触发器 / 任务完成记录 ====================

    public static boolean hasTrigger(ServerPlayer player, String trigger) {
        if (player.getServer() == null || trigger == null || trigger.isEmpty()) {
            return false;
        }
        return data(player).hasTrigger(player.getUUID(), trigger);
    }

    public static void setTrigger(ServerPlayer player, String trigger, boolean value) {
        if (player.getServer() == null || trigger == null || trigger.isEmpty()) {
            return;
        }
        data(player).setTrigger(player.getUUID(), trigger, value);
        sync(player);
    }

    public static void fireTrigger(ServerPlayer player, String trigger) {
        setTrigger(player, trigger, true);
    }

    public static void addCompletedQuest(ServerPlayer player, ResourceLocation questId) {
        if (player.getServer() == null || questId == null) {
            return;
        }
        data(player).addCompletedQuest(player.getUUID(), questId.toString());
    }

    public static boolean hasCompletedQuest(ServerPlayer player, String questId) {
        if (player.getServer() == null || questId == null || questId.isEmpty()) {
            return false;
        }
        if (data(player).completedQuests(player.getUUID()).contains(questId)) {
            return true;
        }
        for (QuestInstance instance : QuestSystem.getInstance().getPlayerQuests(player.getUUID())) {
            if (instance.isCompleted()
                    && instance.getDefinition().id().toString().equals(questId)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 解锁状态 ====================

    /** 玩家当前台子上该节点是否解锁；没有绑定台子时为 false。 */
    public static boolean isUnlocked(ServerPlayer player, String nodeId) {
        TechTableKey table = boundTable(player);
        return table != null && isUnlocked(player, table, nodeId);
    }

    /** 指定条件上下文（或当前台子）上该节点是否解锁。 */
    public static boolean isUnlocked(ServerPlayer player, TechRequirementEngine.Context context,
                                     String nodeId) {
        TechTableKey table = key(player, context);
        return table != null && isUnlocked(player, table, nodeId);
    }

    /**
     * 节点当前是否解锁；声明了 {@code resetAfterTicks} / {@code resetWhen} 的节点满足条件后自动回退。
     */
    private static boolean isUnlocked(ServerPlayer player, TechTableKey table, String nodeId) {
        if (player.getServer() == null || nodeId == null) {
            return false;
        }
        TechSavedData data = data(player);
        if (!data.isUnlocked(table, nodeId)) {
            return false;
        }
        TechNode node = tree.node(nodeId);
        if (node != null && node.resetAfterTicks() > 0) {
            long unlockedAt = data.unlockTime(table, nodeId);
            if (unlockedAt > 0
                    && System.currentTimeMillis() - unlockedAt >= node.resetAfterTicks() * MILLIS_PER_TICK) {
                data.lock(table, nodeId);
                LogManager.serverInfo(TAG, "node '%s' reset on table %s (after %d ticks)",
                        nodeId, table.pos().toShortString(), node.resetAfterTicks());
                return false;
            }
        }
        if (node != null && !node.resetWhen().isEmpty()
                && requirementsMet(player, node.resetWhen(), contextOf(player))) {
            data.lock(table, nodeId);
            LogManager.serverInfo(TAG, "node '%s' reset on table %s (resetWhen met)",
                    nodeId, table.pos().toShortString());
            return false;
        }
        return true;
    }

    public static List<String> unlocked(ServerPlayer player) {
        if (player.getServer() == null) {
            return List.of();
        }
        TechTableKey table = boundTable(player);
        return table == null ? List.of() : List.copyOf(data(player).unlocked(table));
    }

    // ==================== 条件判定 ====================

    public static boolean requirementsMet(ServerPlayer player, List<TechRequirement> requirements,
                                          TechRequirementEngine.Context context) {
        return TechRequirementEngine.satisfied(player, requirements, context);
    }

    public static TechRequirementEngine.Context contextOf(ServerPlayer player) {
        return lastTable.getOrDefault(player.getUUID(), TechRequirementEngine.Context.NONE);
    }

    /** 当前上下文下满足条件的节点 id。 */
    public static List<String> metNodeIds(ServerPlayer player, TechRequirementEngine.Context context) {
        List<String> out = new ArrayList<>();
        for (TechNode node : tree.nodes()) {
            if (requirementsMet(player, node.requirements(), context)) {
                out.add(node.id());
            }
        }
        return out;
    }

    /** 当前上下文下可见的节点 id（visibleWhen 全满足）。 */
    public static List<String> visibleNodeIds(ServerPlayer player, TechRequirementEngine.Context context) {
        List<String> out = new ArrayList<>();
        for (TechNode node : tree.nodes()) {
            if (requirementsMet(player, node.visibleWhen(), context)) {
                out.add(node.id());
            }
        }
        return out;
    }

    public static List<TechSyncPayload.ResearchState> researchStates(ServerPlayer player,
                                                                     TechRequirementEngine.Context context) {
        List<TechSyncPayload.ResearchState> out = new ArrayList<>();
        TechTableKey table = key(player, context);
        long now = System.currentTimeMillis();
        for (TechTree.Research entry : tree.research()) {
            boolean visible = requirementsMet(player, entry.visibleWhen(), context);
            long last = table == null ? 0L : data(player).researchTime(table, entry.id());
            long readyAt = entry.cooldownTicks() <= 0 ? 0L
                    : last + entry.cooldownTicks() * MILLIS_PER_TICK;
            out.add(new TechSyncPayload.ResearchState(entry.id(), visible,
                    Math.max(0L, readyAt), now >= readyAt || last == 0));
        }
        return out;
    }

    // ==================== 解锁 ====================

    public static UnlockResult unlock(ServerPlayer player, String nodeId, BlockPos tablePos) {
        TechTree current = tree;
        if (current == TechTree.EMPTY) {
            return UnlockResult.NO_TREE;
        }
        TechNode node = current.node(nodeId);
        if (node == null) {
            return UnlockResult.UNKNOWN_NODE;
        }
        if (player.getServer() == null) {
            return UnlockResult.NO_TREE;
        }
        TableCheck table = checkTable(player, tablePos);
        if (table == TableCheck.NOT_A_TABLE) {
            return UnlockResult.NOT_AT_TABLE;
        }
        if (table == TableCheck.TOO_FAR) {
            return UnlockResult.TOO_FAR;
        }
        TechTableKey tableKey = TechTableKey.of(player.serverLevel(), tablePos);
        TechRequirementEngine.Context context =
                new TechRequirementEngine.Context(tablePos, TechBlocks.tierAt(player.serverLevel(), tablePos),
                        tableKey.dimension());

        if (isUnlocked(player, tableKey, nodeId)) {
            return UnlockResult.ALREADY_UNLOCKED;
        }
        if (!requirementsMet(player, node.visibleWhen(), context)
                || !requirementsMet(player, node.requirements(), context)) {
            return UnlockResult.MISSING_REQUIREMENT;
        }
        for (TechCost cost : node.costs()) {
            if (!cost.canPay(player, tableKey)) {
                return UnlockResult.INSUFFICIENT_RESOURCES;
            }
        }

        // 条件里声明 consume=true 的物品在解锁时扣除
        for (TechRequirement requirement : node.requirements()) {
            if ("item".equals(requirement.type()) && requirement.consumesItem()) {
                Item item = TechItems.resolve(ResourceLocation.parse(requirement.string("item", "")));
                if (item != null) {
                    TechCost.consume(player, item, requirement.integer("count", 1));
                }
            }
        }
        for (TechCost cost : node.costs()) {
            cost.pay(player, tableKey);
        }
        TechSavedData data = data(player);
        data.unlock(tableKey, nodeId);
        data.recordUnlockTime(tableKey, nodeId, System.currentTimeMillis());
        for (String trigger : node.unlockTriggers()) {
            data.setTrigger(player.getUUID(), trigger, true);
        }
        grantRewards(player, node);
        lastTable.put(player.getUUID(), context);
        sync(player, context);
        QuestSystem.getInstance().checkAllQuests(player);
        LogManager.serverInfo(TAG, "unlocked '%s' on table %s for %s",
                nodeId, tablePos.toShortString(), player.getName().getString());
        return UnlockResult.OK;
    }

    /** 指令/调试：绕过条件与成本直接解锁（作用于玩家当前台子）。 */
    public static boolean forceUnlock(ServerPlayer player, String nodeId) {
        TechNode node = tree.node(nodeId);
        TechTableKey table = boundTable(player);
        if (node == null || table == null || player.getServer() == null) {
            return false;
        }
        TechSavedData data = data(player);
        data.unlock(table, nodeId);
        data.recordUnlockTime(table, nodeId, System.currentTimeMillis());
        for (String trigger : node.unlockTriggers()) {
            data.setTrigger(player.getUUID(), trigger, true);
        }
        sync(player);
        QuestSystem.getInstance().checkAllQuests(player);
        return true;
    }

    public static boolean forceLock(ServerPlayer player, String nodeId) {
        TechTableKey table = boundTable(player);
        if (table == null || player.getServer() == null) {
            return false;
        }
        boolean removed = data(player).lock(table, nodeId);
        sync(player);
        QuestSystem.getInstance().checkAllQuests(player);
        return removed;
    }

    // ==================== 研究提交（按钮） ====================

    public static SubmitResult submitResearch(ServerPlayer player, String researchId, BlockPos tablePos) {
        TechTree.Research entry = tree.research(researchId);
        if (entry == null) {
            return SubmitResult.UNKNOWN;
        }
        TableCheck table = checkTable(player, tablePos);
        if (table == TableCheck.NOT_A_TABLE) {
            return SubmitResult.NOT_AT_TABLE;
        }
        if (table == TableCheck.TOO_FAR) {
            return SubmitResult.TOO_FAR;
        }
        TechTableKey tableKey = TechTableKey.of(player.serverLevel(), tablePos);
        TechRequirementEngine.Context context =
                new TechRequirementEngine.Context(tablePos, TechBlocks.tierAt(player.serverLevel(), tablePos),
                        tableKey.dimension());
        if (!requirementsMet(player, entry.visibleWhen(), context)
                || !requirementsMet(player, entry.requirements(), context)) {
            return SubmitResult.MISSING_REQUIREMENT;
        }
        long now = System.currentTimeMillis();
        long last = data(player).researchTime(tableKey, researchId);
        if (entry.cooldownTicks() > 0
                && last > 0
                && now - last < entry.cooldownTicks() * MILLIS_PER_TICK) {
            return SubmitResult.COOLDOWN;
        }
        for (TechCost cost : entry.costs()) {
            if (!cost.canPay(player, tableKey)) {
                return SubmitResult.INSUFFICIENT_RESOURCES;
            }
        }
        for (TechCost cost : entry.costs()) {
            cost.pay(player, tableKey);
        }
        TechSavedData data = data(player);
        data.recordResearchTime(tableKey, researchId, now);
        data.addPoints(tableKey, entry.points());
        lastTable.put(player.getUUID(), context);
        sync(player, context);
        LogManager.serverInfo(TAG, "%s submitted research '%s' on table %s (+%d points)",
                player.getName().getString(), researchId, tablePos.toShortString(), entry.points());
        return SubmitResult.OK;
    }

    // ==================== 研究台升级 ====================

    public static UpgradeResult upgradeTable(ServerPlayer player, BlockPos pos) {
        TechTree current = tree;
        if (current == TechTree.EMPTY) {
            return UpgradeResult.NO_TREE;
        }
        TableCheck table = checkTable(player, pos);
        if (table == TableCheck.NOT_A_TABLE) {
            return UpgradeResult.NOT_AT_TABLE;
        }
        if (table == TableCheck.TOO_FAR) {
            return UpgradeResult.TOO_FAR;
        }
        int tier = TechBlocks.tierAt(player.serverLevel(), pos);
        if (tier >= current.table().maxTier()) {
            return UpgradeResult.MAX_TIER;
        }
        TechTree.TableSpec.Upgrade upgrade = current.table().upgradeFrom(tier);
        if (upgrade == null) {
            return UpgradeResult.MAX_TIER;
        }
        TechTableKey tableKey = TechTableKey.of(player.serverLevel(), pos);
        for (TechCost cost : upgrade.costs()) {
            if (!cost.canPay(player, tableKey)) {
                return UpgradeResult.INSUFFICIENT_RESOURCES;
            }
        }
        for (TechCost cost : upgrade.costs()) {
            cost.pay(player, tableKey);
        }
        BlockState state = player.serverLevel().getBlockState(pos);
        player.serverLevel().setBlock(pos, TechBlocks.withTier(state, upgrade.to()), Block.UPDATE_ALL);
        PacketDistributor.sendToPlayer(player, new TechTableSyncPayload(pos, upgrade.to()));
        TechRequirementEngine.Context context =
                new TechRequirementEngine.Context(pos, upgrade.to(), tableKey.dimension());
        lastTable.put(player.getUUID(), context);
        sync(player, context);
        LogManager.serverInfo(TAG, "table at %s upgraded %d -> %d for %s",
                pos, tier, upgrade.to(), player.getName().getString());
        return UpgradeResult.OK;
    }

    // ==================== 指令：直接改台子层级 ====================

    /** 指令/调试：直接把研究台层级设为 tier（不收费、不校验升级路径）。 */
    public static boolean setTableTier(ServerPlayer player, BlockPos pos, int tier) {
        if (player.getServer() == null || pos == null || tier < 1) {
            return false;
        }
        if (TechBlocks.tierAt(player.serverLevel(), pos) <= 0) {
            return false;
        }
        BlockState state = player.serverLevel().getBlockState(pos);
        BlockState updated = TechBlocks.withTier(state, tier);
        if (updated.equals(state)) {
            return false;
        }
        player.serverLevel().setBlock(pos, updated, Block.UPDATE_ALL);
        int actual = TechBlocks.tier(updated);
        bindTable(player, pos, actual);
        PacketDistributor.sendToPlayer(player, new TechTableSyncPayload(pos, actual));
        sync(player);
        LogManager.serverInfo(TAG, "table at %s forced to tier %d for %s",
                pos, actual, player.getName().getString());
        return true;
    }

    // ==================== 同步 ====================

    public static void sync(ServerPlayer player) {
        sync(player, contextOf(player));
    }

    public static void sync(ServerPlayer player, TechRequirementEngine.Context context) {
        if (player.getServer() == null) {
            return;
        }
        TechTableKey table = key(player, context);
        PacketDistributor.sendToPlayer(player, new TechSyncPayload(
                table == null ? List.of() : List.copyOf(data(player).unlocked(table)),
                table == null ? 0 : data(player).points(table),
                visibleNodeIds(player, context),
                metNodeIds(player, context),
                researchStates(player, context)));
    }

    /** 记下玩家正在使用的台子（打开界面时调用，保证后续同步使用正确层级）。 */
    public static void bindTable(ServerPlayer player, BlockPos pos, int tier) {
        lastTable.put(player.getUUID(), new TechRequirementEngine.Context(
                pos, tier, player.serverLevel().dimension().location()));
    }

    // ==================== 内部 ====================

    private enum TableCheck {
        OK,
        NOT_A_TABLE,
        TOO_FAR
    }

    private static TableCheck checkTable(ServerPlayer player, BlockPos pos) {
        if (pos == null || TechBlocks.tierAt(player.serverLevel(), pos) <= 0) {
            return TableCheck.NOT_A_TABLE;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > TABLE_REACH_SQR) {
            return TableCheck.TOO_FAR;
        }
        return TableCheck.OK;
    }

    private static void grantRewards(ServerPlayer player, TechNode node) {
        for (TechNode.Reward reward : node.rewards()) {
            Item item = TechItems.resolve(reward.item());
            if (item == null) {
                LogManager.serverWarn(TAG, "奖励物品不存在: %s", reward.item());
                continue;
            }
            int remaining = reward.count();
            while (remaining > 0) {
                int chunk = Math.min(remaining, 64);
                player.getInventory().placeItemBackInInventory(new ItemStack(item, chunk));
                remaining -= chunk;
            }
        }
    }

    private static TechSavedData data(ServerPlayer player) {
        return TechSavedData.get(player.getServer().overworld());
    }

    public enum UnlockResult {
        OK,
        NO_TREE,
        UNKNOWN_NODE,
        NOT_AT_TABLE,
        TOO_FAR,
        ALREADY_UNLOCKED,
        MISSING_REQUIREMENT,
        INSUFFICIENT_RESOURCES
    }

    public enum UpgradeResult {
        OK,
        NO_TREE,
        NOT_AT_TABLE,
        TOO_FAR,
        MAX_TIER,
        INSUFFICIENT_RESOURCES
    }

    public enum SubmitResult {
        OK,
        UNKNOWN,
        NOT_AT_TABLE,
        TOO_FAR,
        MISSING_REQUIREMENT,
        COOLDOWN,
        INSUFFICIENT_RESOURCES
    }
}
