package com.catoxide.catoxidesbattlerebuild.core.hud;

import java.util.Objects;

/**
 * 元素声明「我的尺寸可以由布局包拖动边角来改」——尺寸<b>仍然是一个配置选项</b>，不是新的布局状态。
 *
 * <p>这是有意的设计，不是省事：
 * <ul>
 *   <li>元素本来就在 {@link HudElement#bounds()} 里现读自己的尺寸配置，布局包只要写那个键，
 *       下一帧矩形自然跟着变，无需第二份尺寸真相（也无需 {@code Pose} 缩放——那会带来次像素采样）。</li>
 *   <li>尺寸仍受配置声明里的区间约束，玩家拖不出包作者没允许的取值。</li>
 *   <li>不装布局包时这里就是一句没人读的声明（R3）。</li>
 * </ul>
 *
 * <p>{@link #anchor()} 必须显式声明：元素改尺寸时哪个角留在原地，只有元素自己知道
 * （例：右上角贴边的小地图，边长变大时是<b>左边</b>在动，顶边不动 → {@code TOP_RIGHT}）。
 * 让布局包靠猜屏幕位置反推是不确定的，猜错就会「往右拖反而变小」。
 * 手柄画在固定角的<b>对角</b>，拖到哪都按「到固定角的距离」直接换算取值。
 *
 * <p>量的基准是 {@link HudElement#bounds()} 报的那个矩形。若矩形比配置值多描了一圈边框
 * （小地图就是 {@code size + 2}），写回去的值会比鼠标位置小一个边框宽度——固定几像素，
 * 由区间钳制兜住，不会累积；想把边框算进去的元素，自己把 {@code bounds()} 收成内容框即可。
 *
 * @param option 承载尺寸的选项（必须是整数或浮点，且声明了有限区间才好拖）
 * @param mode   一次拖动改哪些边
 * @param anchor 尺寸变化时保持不动的角
 */
public record HudResize(HudConfigOption option, Mode mode, Anchor anchor) {

    /** {@code SQUARE}：一个值同时是宽和高（小地图）；其余两种只改一条边。 */
    public enum Mode {
        SQUARE,
        WIDTH,
        HEIGHT
    }

    /** 改尺寸时保持不动的那个角。 */
    public enum Anchor {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    public HudResize {
        Objects.requireNonNull(option, "option");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(anchor, "anchor");
    }

    /** 正方形尺寸由一个选项驱动（小地图这类）。 */
    public static HudResize square(HudConfigOption option, Anchor anchor) {
        return new HudResize(option, Mode.SQUARE, anchor);
    }
}
