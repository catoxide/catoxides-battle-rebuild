package com.catoxide.catoxidesbattlerebuild.core.combat;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 弓箭战斗处理器注册表（仿 {@code DurabilityDegradationRegistry}，多槽位）。
 * <p>M5 阶段由主类单一注册点登记 core 实现（F4）；M6 起登记动作移入战斗内容包的
 * {@code register()}。
 * <p>R3：为空时 {@code AbstractArrow} 门不介入，箭矢 = 原版行为。
 */
public final class ArrowCombatHandlers {

    private static final String TAG = "ArrowCombatHandlers";
    private static final List<IArrowCombatHandler> HANDLERS = new CopyOnWriteArrayList<>();

    private ArrowCombatHandlers() {}

    /** 登记处理器（注册阶段调用，按调用顺序生效） */
    public static void register(IArrowCombatHandler handler) {
        HANDLERS.add(handler);
        LogManager.serverInfo(TAG, "ArrowCombatHandler registered: {}", handler.getClass().getName());
    }

    /** 是否有处理器可用（战斗内容是否加载） */
    public static boolean isAvailable() {
        return !HANDLERS.isEmpty();
    }

    /** 全部处理器（注册顺序）；门按序遍历，第一个接管的生效 */
    public static List<IArrowCombatHandler> getAll() {
        return HANDLERS;
    }
}
