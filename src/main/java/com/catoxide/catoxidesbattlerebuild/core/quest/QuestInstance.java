package com.catoxide.catoxidesbattlerebuild.core.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务运行时实例（服务端，可变）。
 * <p>含：状态、目标进度、子任务实例（任务树实例化）、星标、发放信息。
 * <p>进度由内容包组件通过 {@link QuestSystem#updateProgress} 驱动，
 * 或直接 {@link #setGoalProgress} + {@link QuestSystem#checkCompletion}。
 */
public class QuestInstance {

    private final QuestDefinition definition;
    private final UUID playerId;
    private QuestState state = QuestState.ACTIVE;
    private final Map<String, Float> goalProgress = new HashMap<>();
    private final List<QuestInstance> childInstances = new ArrayList<>();
    private QuestInstance parent;
    private boolean starred;
    private long startedAt;
    private String giverId;

    QuestInstance(QuestDefinition definition, UUID playerId, QuestInstance parent, String giverId) {
        this.definition = definition;
        this.playerId = playerId;
        this.parent = parent;
        this.giverId = giverId;
        this.startedAt = System.currentTimeMillis();
    }

    // ========== 查询 ==========

    public QuestDefinition getDefinition() {
        return definition;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public QuestState getState() {
        return state;
    }

    void setState(QuestState state) {
        this.state = state;
    }

    public boolean isActive() {
        return state == QuestState.ACTIVE;
    }

    public boolean isCompleted() {
        return state == QuestState.COMPLETED;
    }

    public boolean isFailed() {
        return state == QuestState.FAILED;
    }

    public QuestInstance getParent() {
        return parent;
    }

    /** 子任务实例（发放时递归创建；完成状态随实例树保留） */
    public List<QuestInstance> getChildInstances() {
        return List.copyOf(childInstances);
    }

    void addChildInstance(QuestInstance child) {
        childInstances.add(child);
    }

    public String getGiverId() {
        return giverId;
    }

    public long getStartedAt() {
        return startedAt;
    }

    // ========== 星标（UI 置顶） ==========

    public boolean isStarred() {
        return starred;
    }

    public void setStarred(boolean starred) {
        this.starred = starred;
    }

    // ========== 进度 ==========

    /** 设置目标进度（内容包组件驱动；之后应调 {@link QuestSystem#checkCompletion}） */
    public void setGoalProgress(String key, float value) {
        goalProgress.put(key, value);
    }

    /** 累加目标进度（如击杀数 +1） */
    public void addGoalProgress(String key, float delta) {
        goalProgress.merge(key, delta, Float::sum);
    }

    public float getGoalProgress(String key) {
        return goalProgress.getOrDefault(key, 0f);
    }

    public Map<String, Float> getGoalProgress() {
        return Map.copyOf(goalProgress);
    }
}
