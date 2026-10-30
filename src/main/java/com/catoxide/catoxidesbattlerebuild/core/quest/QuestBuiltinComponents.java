package com.catoxide.catoxidesbattlerebuild.core.quest;

import com.catoxide.catoxidesbattlerebuild.core.quest.giver.ProximityGiver;
import com.catoxide.catoxidesbattlerebuild.core.quest.goal.ItemPresentGoal;
import com.catoxide.catoxidesbattlerebuild.core.quest.goal.ItemSubmitGoal;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

/**
 * 基座自带的任务组件（giver/goal）注册。
 * <p>提供与具体包无关的通用原语，使纯 JSON 任务（CPE 定义的任务）不需要写 Java 工厂：
 * <ul>
 *   <li>giver {@code proximity_giver} —— 进入半径发放</li>
 *   <li>goal {@code item_present} —— 持有物品 N 个</li>
 *   <li>goal {@code item_submit} —— 持有并交付物品 N 个（完成时扣除）</li>
 * </ul>
 * <p>包在 {@code register()} 里注册同名类型会覆盖内置实现（后注册者生效）。
 */
public final class QuestBuiltinComponents {

    private static final String TAG = "QuestBuiltin";
    private static boolean registered;

    private QuestBuiltinComponents() {
    }

    /** 幂等：重复调用只生效一次。 */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        QuestSystem questSystem = QuestSystem.getInstance();
        questSystem.registerGiverType(ProximityGiver.TYPE, ProximityGiver::fromJson);
        questSystem.registerGoalType(ItemPresentGoal.TYPE, ItemPresentGoal::fromJson);
        questSystem.registerGoalType(ItemSubmitGoal.TYPE, ItemSubmitGoal::fromJson);
        LogManager.serverInfo(TAG, "Registered built-in quest components: giver=proximity_giver, goals=item_present/item_submit");
    }
}
