package com.catoxide.catoxidesbattlerebuild.core.quest;

/**
 * 任务生命周期状态。
 * <p>ACTIVE 进行中 → COMPLETED(成功) / FAILED(失败) → REMOVED(从系统消除)。
 */
public enum QuestState {
    /** 进行中 */
    ACTIVE,
    /** 成功完成 */
    COMPLETED,
    /** 失败 */
    FAILED,
    /** 已消除（从系统移除，仅过渡态，正常不会持久） */
    REMOVED
}
