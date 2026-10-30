package com.catoxide.catoxidesbattlerebuild.core.quest;

/**
 * 自动发放（tick 驱动）判定，纯逻辑无 Minecraft 依赖，可脱离游戏编译测试
 * （见 {@code src/test/java/.../core/quest/QuestGivePolicyTest.java}）。
 *
 * <p>三种定义形态：
 * <ul>
 *   <li><b>一次性</b>（默认）：该玩家收到过一次就永不再发（含已完成/失败）；</li>
 *   <li><b>可重复</b>（{@code repeatable}）：实例进入终态后可以再发，
 *       {@code cooldownTicks} 控制终态到再发的间隔（0 = 下一 tick 就发）；</li>
 *   <li>任何形态下都<b>不允许同时存在两个 ACTIVE 实例</b>。</li>
 * </ul>
 *
 * <p>每次发放都会新建 {@link QuestInstance}（新 UUID 实例 id），终态实例按
 * {@link #TERMINAL_HISTORY_LIMIT} 剪枝 —— 可重复任务不会无限增长。
 */
public final class QuestGivePolicy {

    /** 每个玩家、每个定义保留的终态（完成/失败）实例数上限（超出按发放时间淘汰最旧的） */
    public static final int TERMINAL_HISTORY_LIMIT = 10;

    /** 1 tick = 50 ms（{@code cooldownTicks} 换算用） */
    public static final long MILLIS_PER_TICK = 50L;

    private QuestGivePolicy() {
    }

    /**
     * 该定义现在能否自动发给该玩家。
     *
     * @param repeatable           定义是否可重复发放
     * @param cooldownTicks        终态 → 再发的最短间隔（tick，负数按 0 处理）
     * @param hasActive            玩家是否已有该定义的 ACTIVE 实例
     * @param hasAnyInstance       玩家是否已有该定义的任意实例（含终态）
     * @param lastTerminalAtMillis 该定义最近一次进入终态的时刻（毫秒；无终态传 0）
     * @param nowMillis            当前时刻（毫秒）
     */
    public static boolean canAutoGive(boolean repeatable, int cooldownTicks, boolean hasActive,
                                      boolean hasAnyInstance, long lastTerminalAtMillis, long nowMillis) {
        if (hasActive) {
            return false;
        }
        if (!repeatable) {
            return !hasAnyInstance;
        }
        int cooldown = Math.max(0, cooldownTicks);
        if (cooldown == 0 || lastTerminalAtMillis <= 0) {
            return true;
        }
        return nowMillis - lastTerminalAtMillis >= cooldown * MILLIS_PER_TICK;
    }
}
