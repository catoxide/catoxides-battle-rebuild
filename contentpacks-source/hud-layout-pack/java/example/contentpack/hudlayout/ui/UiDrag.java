package example.contentpack.hudlayout.ui;

/**
 * 拖动取值的纯计算：唯一状态是「按下那一刻面板在哪、鼠标在哪」。
 *
 * <p>每帧都由「起点 + 位移」重算，不累加 {@code dragX/dragY}：原版重建屏、我们自己的
 * 夹边和吸附都不会让误差攒起来（与 HUD 编辑屏的取法一致）。
 *
 * <p>夹边按<b>面板位置</b>算，偏移是最后一步减出来的——所以被夹过的偏移不会反过来
 * 把基准算歪（契约 §4 的不变量）。
 */
public record UiDrag(int baseLeft, int baseTop, int startLeft, int startTop,
                     double startMouseX, double startMouseY) {

    public UiPanelLayout apply(double mouseX, double mouseY, int grid,
                               int screenWidth, int screenHeight,
                               int panelWidth, int panelHeight,
                               UiPanelLayout current) {
        int left = UiGeometry.clampPanel(
                snap(startLeft + (int) Math.round(mouseX - startMouseX), grid), screenWidth, panelWidth);
        int top = UiGeometry.clampPanel(
                snap(startTop + (int) Math.round(mouseY - startMouseY), grid), screenHeight, panelHeight);
        return new UiPanelLayout(left - baseLeft, top - baseTop, current.effects());
    }

    /** 吸附作用在绝对位置上（和 HUD 编辑屏一致），偏移是它减基准的结果。 */
    static int snap(int value, int grid) {
        int size = Math.max(1, grid);
        return Math.round((float) value / size) * size;
    }
}
