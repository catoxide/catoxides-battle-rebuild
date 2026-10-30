package example.contentpack.hudlayout.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudResize;

/**
 * 拉伸手柄的几何：只算「手柄在哪」和「拖到这儿等于多大」，不碰配置、不画东西。
 *
 * <p>取值方式刻意用<b>绝对距离</b>而不是增量：{@code 尺寸 = |鼠标 - 固定角|}。
 * 增量的写法要记「起手时的值」，一旦元素改完尺寸后自己的矩形变了（小地图就是这样，
 * 边长变大时左边会往左跑），下一帧的增量基准就漂了。绝对距离没有这个负担，
 * 也和玩家眼睛看到的「从那个角量过来多大」一致。
 */
public final class HudResizeHandle {

    /** 手柄边长（GUI 缩放单位）。 */
    static final int GRIP_SIZE = 5;
    /** 命中区比可见方块每边多出的余量，鼠标不必精确压在像素上。 */
    private static final int GRIP_SLACK = 2;

    private HudResizeHandle() {
    }

    /** 固定角的<b>对角</b>就是手柄：那里才是「一改就整块跟着长」的那个角。 */
    public static int[] gripRect(HudElement.Bounds rect, HudResize resize) {
        int anchorX = anchorX(rect, resize.anchor());
        int anchorY = anchorY(rect, resize.anchor());
        int x = switch (resize.mode()) {
            case WIDTH, SQUARE -> rect.x() + (anchorX == rect.x() ? rect.width() - GRIP_SIZE : 0);
            case HEIGHT -> rect.x() + (rect.width() - GRIP_SIZE) / 2;
        };
        int y = switch (resize.mode()) {
            case HEIGHT, SQUARE -> rect.y() + (anchorY == rect.y() ? rect.height() - GRIP_SIZE : 0);
            case WIDTH -> rect.y() + (rect.height() - GRIP_SIZE) / 2;
        };
        return new int[]{x, y, GRIP_SIZE, GRIP_SIZE};
    }

    /** 只改宽度的元素，纵向距离没有意义，反之亦然 —— 按模式取用。 */
    public static double sizeFrom(HudElement.Bounds rect, HudResize resize, double mouseX, double mouseY) {
        double horizontal = Math.abs(mouseX - anchorX(rect, resize.anchor()));
        double vertical = Math.abs(mouseY - anchorY(rect, resize.anchor()));
        return switch (resize.mode()) {
            case SQUARE -> Math.max(horizontal, vertical);
            case WIDTH -> horizontal;
            case HEIGHT -> vertical;
        };
    }

    public static boolean hit(int[] grip, double mouseX, double mouseY) {
        return mouseX >= grip[0] - GRIP_SLACK && mouseX < grip[0] + grip[2] + GRIP_SLACK
                && mouseY >= grip[1] - GRIP_SLACK && mouseY < grip[1] + grip[3] + GRIP_SLACK;
    }

    /** 尺寸变化时留在原地的那个点的横坐标。 */
    static int anchorX(HudElement.Bounds rect, HudResize.Anchor anchor) {
        return switch (anchor) {
            case TOP_LEFT, BOTTOM_LEFT -> rect.x();
            case TOP_RIGHT, BOTTOM_RIGHT -> rect.right();
        };
    }

    static int anchorY(HudElement.Bounds rect, HudResize.Anchor anchor) {
        return switch (anchor) {
            case TOP_LEFT, TOP_RIGHT -> rect.y();
            case BOTTOM_LEFT, BOTTOM_RIGHT -> rect.bottom();
        };
    }
}
