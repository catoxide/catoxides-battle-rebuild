package example.contentpack.mapv2.view;

/**
 * 画布像素 ↔ 世界坐标映射（纯算术，绘制和自检共用）。
 *
 * <p>画布坐标以视口左上角为原点、单位是显示器物理像素。{@code bpp <= 1} 时
 * {@code 1/bpp} 是整数，视图原点必须吸附到像素格点（{@code k/bpp}）：否则瓦片、
 * 方块边缘、玩家标记各自 {@code floor}，三者之间最多差 1px —— 在 2~8 像素/方块时
 * 就是半格到八分之一格，看起来像标记朝某个固定方向漂。
 */
public final class MapProjection {

    private MapProjection() {
    }

    /** 视图左边缘/上边缘的世界坐标，已吸附到像素格点。 */
    public static double origin(double center, int viewPixels, double bpp) {
        return snap(center - viewPixels * bpp / 2.0, bpp);
    }

    /** 把世界坐标吸附到像素格点；{@code 1/bpp} 不是整数时原样返回。 */
    public static double snap(double world, double bpp) {
        return onLattice(bpp) ? Math.floor(world / bpp) * bpp : world;
    }

    /** 世界坐标 → 画布内像素偏移（相对视口 left / top）。 */
    public static int pixel(double world, double origin, double bpp) {
        return (int) Math.floor((world - origin) / bpp);
    }

    /** 画布像素偏移 → 世界坐标（右键、放置、状态栏读数用）。 */
    public static double world(double pixel, double origin, double bpp) {
        return origin + pixel * bpp;
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
