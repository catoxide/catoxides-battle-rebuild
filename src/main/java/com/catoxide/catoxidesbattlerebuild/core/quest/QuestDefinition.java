package com.catoxide.catoxidesbattlerebuild.core.quest;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 任务定义（内容包注册的不可变蓝图）。
 * <p>支持任务树拓扑：{@code children} 子任务列表——主任务含子任务，
 * 子任务可再含子任务（递归），由 {@link QuestSystem} 在发放时构造实例树。
 * <p>具体 giver/goal/condition 为内容包实现的组件；本类只做数据容器。
 *
 * @param id              任务唯一 id（内容包命名空间）
 * @param title           标题
 * @param description     描述/提示
 * @param givers          发放源（多个 = 任一触发即发放）
 * @param goals           目标（全部完成 = 任务完成）
 * @param conditions      完成门槛（额外条件，全部满足才可完成）
 * @param failConditions  失败条件（任一触发 = 任务失败）
 * @param children        子任务（任务树拓扑；发放时递归创建实例）
 * @param location        任务地点（可选；UI 距离显示用，如建筑坐标/区域中心）
 * @param priority        排序权重（正数靠前；UI 无激活主任务时的 top-N 排序用）
 */
public record QuestDefinition(
        ResourceLocation id,
        Component title,
        Component description,
        List<IQuestGiver> givers,
        List<IQuestGoal> goals,
        List<IQuestCondition> conditions,
        List<IQuestCondition> failConditions,
        List<QuestDefinition> children,
        Vec3 location,
        float priority
) {
    public QuestDefinition {
        givers = givers == null ? List.of() : List.copyOf(givers);
        goals = goals == null ? List.of() : List.copyOf(goals);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        failConditions = failConditions == null ? List.of() : List.copyOf(failConditions);
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** 是否为纯容器任务（无自身 goal，仅由子任务决定完成） */
    public boolean isContainerOnly() {
        return goals.isEmpty();
    }
}
