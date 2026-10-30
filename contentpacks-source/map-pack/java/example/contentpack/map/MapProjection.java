package example.contentpack.map;

/**
 * 全屏地图的 world → 屏幕像素映射（纯算术，绘制和自检共用）。
 *
 * <p>放大到「一个方块占多个像素」时 {@code 1/bpp} 是整数，视图原点必须吸附到像素格点
 * （{@code k/bpp}）。否则方块边缘、玩家标记、瓦片各自 {@code floor}，三者之间最多差 1px；
 * 在 2~8 px/方块时这 1px 就是半格到八分之一格，看起来像玩家标记朝某个方向漂了，而且
 * 偏多少只由脚下坐标的小数部分决定（所以看上去是「固定的位置」）。
 */
public final class MapProjection {

    private MapProjection() {
    }

    /** 视图左边缘/上边缘的世界坐标，已吸附到像素格点。 */
    public static double origin(double center, int view, double bpp) {
        return snap(center - view * bpp / 2.0, bpp);
    }

    /** 把世界坐标吸附到像素格点；{@code 1/bpp} 不是整数时原样返回。 */
    public static double snap(double world, double bpp) {
        return onLattice(bpp) ? Math.floor(world / bpp) * bpp : world;
    }

    /** 世界坐标 → 视口内像素偏移（相对 left / top）。 */
    public static int pixel(double world, double origin, double bpp) {
        return (int) Math.floor((world - origin) / bpp);
    }

    /** 方块边界能否落在整像素上，即 {@code 1/bpp} 是不是整数。 */
    public static boolean onLattice(double bpp) {
        if (!(bpp > 0.0)) {
            return false;
        }
        double pixelsPerBlock = 1.0 / bpp;
        return Math.abs(pixelsPerBlock - Math.rint(pixelsPerBlock)) < 1e-9;
    }
}
