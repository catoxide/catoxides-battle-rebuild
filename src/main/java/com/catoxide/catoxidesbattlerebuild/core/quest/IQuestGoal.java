package com.catoxide.catoxidesbattlerebuild.core.quest;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/**
 * 任务目标（内容包实现细则）。
 * <p>一个任务可有多个 goal，全部完成即任务完成。类型自由扩展
 * （建筑/击杀/采集/送达……内容包实现并注册 type 工厂）。
 */
public interface IQuestGoal {

    /** 目标类型标识（注册用，如 "build"/"kill"/"collect"） */
    String getType();

    /** 是否已完成（基座在 updateProgress/检查时调用） */
    boolean isComplete(QuestInstance quest, ServerPlayer player);

    /** 当前进度（供 UI 显示；key=进度项名，value=当前值） */
    Map<String, Float> getProgress(QuestInstance quest, ServerPlayer player);

    /**
     * 进度事件回调（可选）：内容包组件监听游戏事件后，通过
     * {@link QuestSystem#updateProgress(QuestInstance, String, float)} 更新进度，
     * 或直接驱动本 goal 的内部状态。
     */
    default void onProgressUpdate(QuestInstance quest, ServerPlayer player, String key, float value) {
    }
}
