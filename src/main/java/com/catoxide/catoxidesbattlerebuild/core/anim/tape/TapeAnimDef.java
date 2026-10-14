package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

/**
 * 动画绑定（状态/段 → Spark 动画实例的声明）。
 * <p>播放层由 AnimDriver（P2 = Spark AnimController 适配）消费：
 * group = AnimGroups 分层（1=身体、4=武器层…），blendIn = 进入过渡秒。
 *
 * @param group   AnimGroups 层号
 * @param name    动画名（spark_animations 内）
 * @param loop    循环（false = 单次，ONCE 语义：不中途打断由驱动层沿用 Spark 预设规则）
 * @param blendIn 进入过渡秒
 * @param altTag  可选：tag 名；altIf 匹配时动画名换为 altThen（如 hit_dir=front→hit_front / back→hit_back）
 * @param altIf   altTag 的匹配值
 * @param altThen 匹配时使用的动画名
 */
public record TapeAnimDef(
        int group,
        String name,
        boolean loop,
        float blendIn,
        String altTag,
        String altIf,
        String altThen) {

    public TapeAnimDef(int group, String name, boolean loop, float blendIn) {
        this(group, name, loop, blendIn, null, null, null);
    }

    /** 按 tag 解析最终动画名（alt 规则） */
    public String resolveName(java.util.Map<String, String> tags) {
        if (altTag != null && altThen != null) {
            String v = tags.get(altTag);
            if (altIf == null ? v != null : altIf != null && altIf.equals(v)) {
                return altThen;
            }
        }
        return name;
    }

    /** 绑定签名（驱动层据此判断是否需要重进动画） */
    public String bindingKey(java.util.Map<String, String> tags) {
        return group + ":" + resolveName(tags);
    }
}
