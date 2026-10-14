package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

import java.util.List;

/**
 * 纸带段：序列态的一段（时间只前进：t 到达 untilSec → 进入下一段，不可回退）。
 *
 * @param untilSec    段终点（状态本地时钟 t，秒；段内单调递增）
 * @param anim        本段动画绑定
 * @param milestones  段内里程碑（t 处单发动作）
 * @param next        可选：本段结束后的目标状态（仅末段有意义；缺省 = 停在末段最后一帧等待条件转移，
 *                    对应 hold_on_last_frame 语义——攻击收招停住等下一次判定）
 */
public record TapeSegment(double untilSec, TapeAnimDef anim, List<TapeMilestone> milestones, String next) {

    public TapeSegment(double untilSec, TapeAnimDef anim, List<TapeMilestone> milestones) {
        this(untilSec, anim, milestones, null);
    }
}
