package com.catoxide.catoxidesbattlerebuild.core.ui;

/**
 * 借 Pose 平移渲染时的 scissor 补偿（只给 mixin 用；跨 mixin 共享状态住普通类）。
 *
 * <p>原版 {@code GuiGraphics#enableScissor} 的矩形是屏幕坐标，**不进 Pose**；
 * 而 {@code InventoryScreen#renderBg} 的玩家模型正是「先 enableScissor，再在 Pose 里画」。
 * 布局层为了挪背景会把整段 {@code renderBg} 平移（{@code ContainerBackgroundShiftMixin}），
 * 模型跟着 Pose 走、裁剪框却留在原地——竖向挪出原居中位置就只剩那一块可见。
 *
 * <p>所以平移 Pose 的一侧同时登记位移，{@code GuiGraphics#applyScissor} 的 mixin 补上。
 * 只做 Y：现在的平移都只动纵向。
 */
public final class ScissorShift {

    private static int offsetY;

    private ScissorShift() {
    }

    /** 与 Pose 的 translate 同进同出；可嵌套，位移相加。 */
    public static void begin(int dy) {
        offsetY += dy;
    }

    public static void end(int dy) {
        offsetY -= dy;
    }

    public static int offsetY() {
        return offsetY;
    }
}
