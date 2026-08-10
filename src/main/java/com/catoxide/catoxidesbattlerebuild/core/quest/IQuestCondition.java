package com.catoxide.catoxidesbattlerebuild.core.quest;

import net.minecraft.server.level.ServerPlayer;

/**
 * 任务额外条件（内容包实现细则）。
 * <p>用于：完成门槛（如"须在 X 区域完成"）或失败条件（区域被破坏等）。
 */
public interface IQuestCondition {

    /**
     * 条件是否满足。
     *
     * @param quest  任务实例
     * @param player 玩家
     * @return true = 条件满足
     */
    boolean test(QuestInstance quest, ServerPlayer player);
}
