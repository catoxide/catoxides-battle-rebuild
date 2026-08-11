package com.catoxide.catoxidesbattlerebuild.core.quest;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 任务系统基座（主 mod 核心，纯逻辑）。
 * <p>提供：任务定义注册、组件工厂注册（内容包挂接）、生命周期
 * （发放/进度/完成/失败/消除）、任务树拓扑实例化、查询排序、星标、距离、事件广播。
 * <p>内容包实现细则（IQuestGiver/IQuestGoal/IQuestCondition），本类不包含任何具体任务逻辑。
 * <p>V1 不做：持久化（服务器重启任务丢失，TODO 后续）、超时（仅靠 failConditions）。
 */
public final class QuestSystem {

    private static final QuestSystem INSTANCE = new QuestSystem();
    private static final String TAG = "QuestSystem";

    /** 任务定义注册表（内容包 registerQuest 填充） */
    private final Map<ResourceLocation, QuestDefinition> definitions = new ConcurrentHashMap<>();
    /** 玩家 → 激活任务的根实例列表（子实例在根实例树内，不重复存储） */
    private final Map<UUID, List<QuestInstance>> playerRootQuests = new HashMap<>();

    /** 组件工厂注册表：type 字符串 → 工厂（内容包注册，JSON 定义解析用） */
    private final Map<String, Function<JsonObject, IQuestGiver>> giverFactories = new ConcurrentHashMap<>();
    private final Map<String, Function<JsonObject, IQuestGoal>> goalFactories = new ConcurrentHashMap<>();
    private final Map<String, Function<JsonObject, IQuestCondition>> conditionFactories = new ConcurrentHashMap<>();

    private QuestSystem() {
    }

    public static QuestSystem getInstance() {
        return INSTANCE;
    }

    // ========== 定义注册（内容包） ==========

    public void registerDefinition(QuestDefinition definition) {
        definitions.put(definition.id(), definition);
        LogManager.serverInfo(TAG, "Registered quest definition '%s': children=%s, goals=%s, givers=%s",
                definition.id(), definition.children().size(), definition.goals().size(), definition.givers().size());
    }

    public QuestDefinition getDefinition(ResourceLocation id) {
        return definitions.get(id);
    }

    /** 全部已注册定义（供 tick 驱动/发放检查/UI 遍历） */
    public java.util.Collection<QuestDefinition> getDefinitions() {
        return java.util.Collections.unmodifiableCollection(definitions.values());
    }

    // ========== 组件工厂注册（内容包挂接） ==========

    public void registerGiverType(String type, Function<JsonObject, IQuestGiver> factory) {
        giverFactories.put(type, factory);
    }

    public void registerGoalType(String type, Function<JsonObject, IQuestGoal> factory) {
        goalFactories.put(type, factory);
    }

    public void registerConditionType(String type, Function<JsonObject, IQuestCondition> factory) {
        conditionFactories.put(type, factory);
    }

    public Optional<Function<JsonObject, IQuestGiver>> getGiverFactory(String type) {
        return Optional.ofNullable(giverFactories.get(type));
    }

    public Optional<Function<JsonObject, IQuestGoal>> getGoalFactory(String type) {
        return Optional.ofNullable(goalFactories.get(type));
    }

    public Optional<Function<JsonObject, IQuestCondition>> getConditionFactory(String type) {
        return Optional.ofNullable(conditionFactories.get(type));
    }

    // ========== 生命周期 ==========

    /**
     * 发放任务（含子任务树递归实例化）。
     *
     * @param player  目标玩家
     * @param defId   任务定义 id
     * @param giverId 发放源标识（如 "region:north_gate" / "npc:builder"），仅记录
     * @return 根任务实例；定义不存在返回 null
     */
    public QuestInstance giveQuest(ServerPlayer player, ResourceLocation defId, String giverId) {
        QuestDefinition def = definitions.get(defId);
        if (def == null) {
            LogManager.serverWarn(TAG, "giveQuest: unknown definition '%s'", defId);
            return null;
        }
        return giveQuest(player, def, giverId);
    }

    /**
     * 发放任务（直接传定义）。
     * <p>防重复：玩家已有该定义的激活实例时返回 null（拒绝重复发放）。
     */
    public QuestInstance giveQuest(ServerPlayer player, QuestDefinition def, String giverId) {
        boolean hasActive = playerRootQuests.getOrDefault(player.getUUID(), List.of()).stream()
                .anyMatch(q -> q.isActive() && q.getDefinition().id().equals(def.id()));
        if (hasActive) {
            LogManager.serverInfo(TAG, "giveQuest rejected: player {} already has active '{}'",
                    player.getUUID(), def.id());
            return null;
        }
        QuestInstance root = createInstanceTree(player, def, null, giverId);
        playerRootQuests.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).add(root);
        for (IQuestGiver giver : def.givers()) {
            try {
                giver.onGive(player, root);
            } catch (Exception e) {
                LogManager.serverError(TAG, "giver.onGive threw: %s", e.getMessage(), e);
            }
        }
        NeoForge.EVENT_BUS.post(new QuestEvents.QuestGivenEvent(root, player, giverId));
        LogManager.serverInfo(TAG, "Quest '%s' given to player %s (giver=%s)", def.id(), player.getName().getString(), giverId);
        return root;
    }

    /** 递归创建实例树（父 → 子） */
    private QuestInstance createInstanceTree(ServerPlayer player, QuestDefinition def, QuestInstance parent, String giverId) {
        QuestInstance instance = new QuestInstance(def, player.getUUID(), parent, giverId);
        for (QuestDefinition childDef : def.children()) {
            instance.addChildInstance(createInstanceTree(player, childDef, instance, giverId));
        }
        return instance;
    }

    /**
     * 更新目标进度（内容包组件在监听到游戏事件后调用），
     * 自动广播进度事件并触发完成/失败检查。
     */
    public void updateProgress(QuestInstance quest, String key, float value) {
        if (quest == null || !quest.isActive()) {
            return;
        }
        quest.setGoalProgress(key, value);
        ServerPlayer player = findPlayer(quest.getPlayerId());
        if (player != null) {
            NeoForge.EVENT_BUS.post(new QuestEvents.QuestProgressEvent(quest, player));
        }
        checkCompletion(quest);
    }

    /**
     * 检查玩家所有任务（递归整棵树，先子后父）。
     * <p>内容包/驱动每 tick 调用此方法即可让所有任务（含子任务）完成/失败条件被评估——
     * 仅遍历根任务（getActiveQuestsSorted）会漏掉子任务的完成检查。
     */
    public void checkAllQuests(ServerPlayer player) {
        List<QuestInstance> roots = playerRootQuests.getOrDefault(player.getUUID(), List.of());
        for (QuestInstance root : roots) {
            checkTree(root, player);
        }
    }

    private void checkTree(QuestInstance quest, ServerPlayer player) {
        for (QuestInstance child : quest.getChildInstances()) {
            checkTree(child, player);
        }
        if (quest.isActive()) {
            checkCompletion(quest);
        }
    }

    /** 检查任务完成/失败条件（内容包更新进度后手动调用，或经 updateProgress 自动调用）。 */
    public void checkCompletion(QuestInstance quest) {
        if (quest == null || !quest.isActive()) {
            return;
        }
        ServerPlayer player = findPlayer(quest.getPlayerId());
        if (player == null) {
            return;
        }
        // 失败条件优先
        for (IQuestCondition cond : quest.getDefinition().failConditions()) {
            try {
                if (cond.test(quest, player)) {
                    failQuest(quest, "fail_condition:" + cond.getClass().getSimpleName());
                    return;
                }
            } catch (Exception e) {
                LogManager.serverError(TAG, "failCondition.test threw: %s", e.getMessage(), e);
            }
        }
        // 完成检查：容器任务（无自身 goal）= 所有子任务完成；否则所有 goals 完成 + 完成门槛满足
        boolean allGoalsDone;
        if (quest.getDefinition().goals().isEmpty()) {
            // 任务树容器：依赖子任务全部完成
            allGoalsDone = quest.getChildInstances().stream()
                    .allMatch(QuestInstance::isCompleted);
        } else {
            allGoalsDone = quest.getDefinition().goals().stream()
                    .allMatch(g -> safeIsComplete(g, quest, player));
        }
        if (!allGoalsDone) {
            return;
        }
        for (IQuestCondition cond : quest.getDefinition().conditions()) {
            try {
                if (!cond.test(quest, player)) {
                    return;
                }
            } catch (Exception e) {
                LogManager.serverError(TAG, "condition.test threw: %s", e.getMessage(), e);
                return;
            }
        }
        completeQuest(quest);
    }

    private static boolean safeIsComplete(IQuestGoal goal, QuestInstance quest, ServerPlayer player) {
        try {
            return goal.isComplete(quest, player);
        } catch (Exception e) {
            LogManager.serverError(TAG, "goal.isComplete threw: %s", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 标记任务完成。子任务完成时自动检查父任务（任务树传播）。
     */
    /**
     * 标记任务完成（正常逻辑）：先校验可完成性——
     * 容器任务（无自身 goal）= 所有子任务已完成；有 goal = 全部完成 + 完成门槛满足。
     * 校验不通过返回 false（不完成）。
     */
    public boolean completeQuest(QuestInstance quest) {
        if (quest == null || !quest.isActive()) {
            return false;
        }
        if (!canComplete(quest)) {
            LogManager.serverInfo(TAG, "Quest '%s' completion rejected (prerequisites not met, player=%s)",
                    quest.getDefinition().id(), quest.getPlayerId());
            return false;
        }
        doComplete(quest);
        return true;
    }

    /**
     * 强制完成（调试/管理用）：递归级联完成所有子任务后完成自身，
     * 保持任务树状态一致（不会出现"主任务完成但子任务未完成"）。
     */
    public boolean completeQuestForce(QuestInstance quest) {
        if (quest == null || !quest.isActive()) {
            return false;
        }
        for (QuestInstance child : quest.getChildInstances()) {
            completeQuestForce(child);
        }
        doComplete(quest);
        return true;
    }

    /** 可完成性校验（容器任务 = 子任务全完成；有 goal = 全完成 + 条件满足） */
    private boolean canComplete(QuestInstance quest) {
        ServerPlayer player = findPlayer(quest.getPlayerId());
        if (player == null) {
            return false;
        }
        if (quest.getDefinition().goals().isEmpty()) {
            return quest.getChildInstances().stream().allMatch(QuestInstance::isCompleted);
        }
        if (!quest.getDefinition().goals().stream()
                .allMatch(g -> safeIsComplete(g, quest, player))) {
            return false;
        }
        for (IQuestCondition cond : quest.getDefinition().conditions()) {
            try {
                if (!cond.test(quest, player)) {
                    return false;
                }
            } catch (Exception e) {
                LogManager.serverError(TAG, "condition.test threw: {}", e.getMessage(), e);
                return false;
            }
        }
        return true;
    }

    /** 实际完成动作（状态 + 广播 + 父检查传播） */
    private void doComplete(QuestInstance quest) {
        quest.setState(QuestState.COMPLETED);
        ServerPlayer player = findPlayer(quest.getPlayerId());
        if (player != null) {
            NeoForge.EVENT_BUS.post(new QuestEvents.QuestCompletedEvent(quest, player));
        }
        LogManager.serverInfo(TAG, "Quest '%s' completed (player=%s)", quest.getDefinition().id(),
                quest.getPlayerId());
        // 任务树传播：父任务在子完成后重新检查
        QuestInstance parent = quest.getParent();
        if (parent != null && parent.isActive()) {
            checkCompletion(parent);
        }
    }

    /**
     * 标记任务失败。
     * <p>级联：子任务也失败（任务树状态一致——父失败则整个树失去意义）；
     * 父任务也失败（向上传播）。
     */
    public boolean failQuest(QuestInstance quest, String reason) {
        if (quest == null || !quest.isActive()) {
            return false;
        }
        // 级联子任务失败（先子后父，保证整树一致）
        for (QuestInstance child : quest.getChildInstances()) {
            failQuest(child, reason + ":child");
        }
        quest.setState(QuestState.FAILED);
        ServerPlayer player = findPlayer(quest.getPlayerId());
        if (player != null) {
            NeoForge.EVENT_BUS.post(new QuestEvents.QuestFailedEvent(quest, player, reason));
        }
        LogManager.serverInfo(TAG, "Quest '%s' failed (player=%s, reason=%s)", quest.getDefinition().id(),
                quest.getPlayerId(), reason);
        // 失败传播：父任务也失败（子失败 → 任务树整体失败）
        QuestInstance parent = quest.getParent();
        if (parent != null && parent.isActive()) {
            failQuest(parent, "child_failed:" + quest.getDefinition().id());
        }
        return true;
    }

    /**
     * 消除任务（从系统移除；完成/失败后的清理，或外部强制取消）。
     */
    public void removeQuest(QuestInstance quest) {
        if (quest == null) {
            return;
        }
        List<QuestInstance> roots = playerRootQuests.get(quest.getPlayerId());
        if (roots != null) {
            // 若为根实例直接移除；若为子实例则从其父移除
            boolean removed = roots.remove(quest);
            if (!removed && quest.getParent() != null) {
                removeChildFrom(quest.getParent(), quest);
            }
        }
        quest.setState(QuestState.REMOVED);
        ServerPlayer player = findPlayer(quest.getPlayerId());
        if (player != null) {
            NeoForge.EVENT_BUS.post(new QuestEvents.QuestRemovedEvent(quest, player));
        }
        LogManager.serverInfo(TAG, "Quest '%s' removed (player=%s)", quest.getDefinition().id(), quest.getPlayerId());
    }

    private void removeChildFrom(QuestInstance parent, QuestInstance child) {
        // QuestInstance 的 childInstances 是 List.copyOf 只读返回——通过反射不可取；
        // 实际 child 移除走父状态机（父完成/失败后整树移除由外部调 removeQuest(root)）。
        LogManager.serverDebug(TAG, "Child quest removed implicitly via parent lifecycle");
    }

    // ========== 查询（供 UI/内容包） ==========

    /**
     * 玩家全部激活任务的根实例（含完成/失败但未消除的，由 UI 过滤 state）。
     */
    public List<QuestInstance> getPlayerQuests(UUID playerId) {
        return List.copyOf(playerRootQuests.getOrDefault(playerId, List.of()));
    }

    /**
     * 玩家激活中的任务，按 UI 优先级排序：
     * 星标置顶 → 定义 priority → 距离（近优先，无地点者排后）→ 发放时间（早优先）。
     *
     * @param playerPos 玩家位置（距离排序用；可为 null 则跳过距离排序）
     */
    public List<QuestInstance> getActiveQuestsSorted(UUID playerId, Vec3 playerPos) {
        return getPlayerQuests(playerId).stream()
                .filter(QuestInstance::isActive)
                .sorted(activeComparator(playerPos))
                .toList();
    }

    private Comparator<QuestInstance> activeComparator(Vec3 playerPos) {
        return (a, b) -> {
            int byStar = Boolean.compare(b.isStarred(), a.isStarred());
            if (byStar != 0) return byStar;
            int byPriority = Float.compare(b.getDefinition().priority(), a.getDefinition().priority());
            if (byPriority != 0) return byPriority;
            if (playerPos != null) {
                double da = questDistance(a, playerPos);
                double db = questDistance(b, playerPos);
                if (da >= 0 && db >= 0) {
                    int byDist = Double.compare(da, db);
                    if (byDist != 0) return byDist;
                } else if (da >= 0) {
                    return -1;
                } else if (db >= 0) {
                    return 1;
                }
            }
            return Long.compare(a.getStartedAt(), b.getStartedAt());
        };
    }

    /**
     * 任务与玩家的距离（米）。定义无 location 返回 -1。
     */
    public double questDistance(QuestInstance quest, Vec3 playerPos) {
        Vec3 loc = quest.getDefinition().location();
        if (loc == null || playerPos == null) {
            return -1;
        }
        return playerPos.distanceTo(loc);
    }

    /** 星标置顶标记（UI 展开页操作） */
    public void setStarred(QuestInstance quest, boolean starred) {
        quest.setStarred(starred);
    }

    // ========== 内部 ==========

    private ServerPlayer findPlayer(UUID uuid) {
        net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(uuid);
    }

    /** 清空所有任务（世界重载/测试） */
    public void clearAll() {
        playerRootQuests.clear();
        LogManager.serverInfo(TAG, "All player quests cleared");
    }
}
