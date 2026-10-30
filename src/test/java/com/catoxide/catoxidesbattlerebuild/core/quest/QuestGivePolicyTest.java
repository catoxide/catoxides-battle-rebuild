package com.catoxide.catoxidesbattlerebuild.core.quest;

/**
 * 自动发放策略单测（纯 main()，无 Minecraft 依赖，javac 独立编译运行）。
 * <p>运行：
 * <pre>
 * javac -d out src/main/java/.../core/quest/QuestGivePolicy.java \
 *            src/test/java/.../core/quest/QuestGivePolicyTest.java
 * java -cp out com.catoxide.catoxidesbattlerebuild.core.quest.QuestGivePolicyTest
 * </pre>
 * <p>覆盖：一次性/可重复 × 无实例/ACTIVE/终态、cooldown 边界、缺失时间戳、负数冷却。
 */
public final class QuestGivePolicyTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond, Object extra) {
        if (cond) { pass++; System.out.println("  \u2713 " + name); }
        else { fail++; System.out.println("  \u2717 " + name + (extra != null ? " [" + extra + "]" : "")); }
    }

    static void check(String name, boolean cond) { check(name, cond, null); }

    private static final long NOW = 1_000_000_000L;
    private static final long TICK = QuestGivePolicy.MILLIS_PER_TICK;

    public static void main(String[] args) {
        System.out.println("== 1. 一次性任务 ==");
        check("从未发放 → 可以发", QuestGivePolicy.canAutoGive(false, 0, false, false, 0, NOW));
        check("有终态实例 → 不再发", !QuestGivePolicy.canAutoGive(false, 0, false, true, NOW - 1, NOW));
        check("有 ACTIVE → 不发", !QuestGivePolicy.canAutoGive(false, 0, true, true, 0, NOW));

        System.out.println("== 2. 可重复任务 ==");
        check("有 ACTIVE → 不发（禁止同定义并行）",
                !QuestGivePolicy.canAutoGive(true, 0, true, true, 0, NOW));
        check("终态 + cooldown=0 → 下一 tick 就发",
                QuestGivePolicy.canAutoGive(true, 0, false, true, NOW - 1, NOW));
        check("终态 + cooldown=200 且只过了 100 tick → 不发",
                !QuestGivePolicy.canAutoGive(true, 200, false, true, NOW - 100 * TICK, NOW));
        check("终态 + cooldown=200 且刚好过 200 tick → 发",
                QuestGivePolicy.canAutoGive(true, 200, false, true, NOW - 200 * TICK, NOW));
        check("终态 + cooldown=200 且过了 201 tick → 发",
                QuestGivePolicy.canAutoGive(true, 200, false, true, NOW - 201 * TICK, NOW));
        check("终态但缺 endedAt（旧存档）→ 不永久卡死",
                QuestGivePolicy.canAutoGive(true, 200, false, true, 0, NOW));
        check("负数 cooldown 按 0 处理",
                QuestGivePolicy.canAutoGive(true, -5, false, true, NOW - 1, NOW));

        System.out.println("== 3. 剪枝上限 ==");
        check("终态历史上限 > 0", QuestGivePolicy.TERMINAL_HISTORY_LIMIT > 0,
                QuestGivePolicy.TERMINAL_HISTORY_LIMIT);

        System.out.println();
        System.out.println("结果: " + pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
