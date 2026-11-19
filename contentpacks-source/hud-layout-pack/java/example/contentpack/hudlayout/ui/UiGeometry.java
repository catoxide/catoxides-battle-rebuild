package example.contentpack.hudlayout.ui;

/**
 * 面板几何的纯计算，单独放开给自检用。
 */
public final class UiGeometry {

    /** 面板至少要有这么多像素留在屏幕里：可以收纳到边上，但不能整个消失。 */
    public static final int MIN_VISIBLE = 16;

    private UiGeometry() {
    }

    /**
     * 把面板位置夹到「至少留一截在屏幕里」。
     *
     * <p>只限不许完全出屏；允许故意把一半收进屏幕边缘外（契约 §7-7）。
     */
    public static int clampPanel(int value, int screenSize, int panelSize) {
        int min = MIN_VISIBLE - panelSize;
        int max = screenSize - MIN_VISIBLE;
        return Math.max(min, Math.min(value, max));
    }
}
