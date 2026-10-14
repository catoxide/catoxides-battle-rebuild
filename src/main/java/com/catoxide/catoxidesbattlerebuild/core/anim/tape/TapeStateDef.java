package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

import java.util.List;

/**
 * 状态定义。两种形态（由 segments 是否为空决定）：
 * <ul>
 *   <li><b>循环态</b>（segments 空）：播 loopAnim（可循环），靠 exits 条件离开</li>
 *   <li><b>序列态</b>（segments 非空）= 纸带：本地时钟 t 从 0 前进，段按 untilSec 推进，
 *       里程碑单发提交；末段 next 指向目标状态（缺省 = 停在末段最后一帧等条件转移）</li>
 * </ul>
 *
 * @param name       状态名（轨内唯一）
 * @param loopAnim   循环态动画绑定（序列态忽略——序列态用各段 anim）
 * @param segments   纸带段列表（时间只前进）
 * @param exits      条件转移（priority 降序检查；前跳，不回退）
 * @param onEnter    进入时执行的动作（每次进入执行一次）
 * @param terminal   终态（冻结：不再推进/转移，仅保留动画绑定）
 */
public record TapeStateDef(String name, TapeAnimDef loopAnim, List<TapeSegment> segments,
                           List<TapeExit> exits, List<TapeAction> onEnter, boolean terminal) {

    public boolean isSequence() {
        return segments != null && !segments.isEmpty();
    }
}
