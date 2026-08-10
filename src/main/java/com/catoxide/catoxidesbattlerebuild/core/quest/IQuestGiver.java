package com.catoxide.catoxidesbattlerebuild.core.quest;

import net.minecraft.server.level.ServerPlayer;

/**
 * 任务发放源（内容包实现细则）。
 * <p>基座只定义接口与调用时机；具体发放逻辑（区域/NPC/物品/链式等）由内容包实现。
 */
public interface IQuestGiver {

    /**
     * 是否应给该玩家发放本任务（每 tick / 事件触发时被调用）。
     * <p>内容包实现例：玩家进入区域 / 与 NPC 交互 / 使用物品 / 链式（上游任务完成）。
     *
     * @param player 目标玩家
     * @return true = 发放
     */
    boolean canGive(ServerPlayer player);

    /** 发放瞬间的钩子（可选：如播放音效/扣除物品） */
    default void onGive(ServerPlayer player, QuestInstance quest) {
    }
}
