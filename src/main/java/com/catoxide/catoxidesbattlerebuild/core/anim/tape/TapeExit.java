package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

/**
 * 条件转移（前跳）：每 tick 按 priority 降序检查，命中即跳转目标状态（t 归零，不回退不重放）。
 *
 * @param when      守卫条件（声明式词汇，见 {@link TapeCondition}）
 * @param to        目标状态名（同轨内）
 * @param priority  优先级（大者先；死亡类 100，受击类 10，常规 0）
 * @param afterSec  进入当前状态 afterSec 秒后才允许（数据化 hold 窗口；缺省 0）
 * @param beforeSec beforeSec 秒后失效（缺省 = 无限；与 afterSec 组成窗口 [after, before)）
 */
public record TapeExit(TapeCondition when, String to, int priority, double afterSec, double beforeSec) {

    public TapeExit(TapeCondition when, String to) {
        this(when, to, 0, 0.0, -1.0);
    }

    public TapeExit(TapeCondition when, String to, int priority) {
        this(when, to, priority, 0.0, -1.0);
    }

    public TapeExit(TapeCondition when, String to, int priority, double afterSec) {
        this(when, to, priority, afterSec, -1.0);
    }
}
