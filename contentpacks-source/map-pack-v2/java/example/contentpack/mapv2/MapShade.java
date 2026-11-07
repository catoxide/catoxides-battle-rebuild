package example.contentpack.mapv2;

/**
 * 像素着色工具（地图瓦片生成用）。
 *
 * <p>从旧包 {@code MapMinimap} 里抽出来：瓦片纹理和方块顶面都调它，和小地图本身无关。
 */
public final class MapShade {

    /** 相邻像素高度未知（瓦片外 / 该格未采集）。 */
    public static final int NO_HEIGHT = Integer.MIN_VALUE;

    private MapShade() {
    }

    /**
     * 高度明暗 + 流体修正。
     * <p>世界底部 55% 亮度、顶部 100%；水按深度最多再压暗 45%，岩浆提亮 12%。
     *
     * @param fluid 正数水深、负数岩浆深度、0 非流体
     */
    public static int applyShade(int argb, int y, int minY, int heightRange, int fluid) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        float t = (y - minY) / (float) heightRange;
        t = Math.max(0f, Math.min(1f, t));
        float shade = 0.55f + 0.45f * t;
        if (fluid > 0) {
            shade *= 1f - Math.min(0.45f, fluid / 64f);
        } else if (fluid < 0) {
            shade *= 1.12f;
        }
        r = Math.min(255, (int) (r * shade));
        g = Math.min(255, (int) (g * shade));
        b = Math.min(255, (int) (b * shade));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 立体光暗：按西北向坡降给像素调亮/压暗。
     *
     * <p>跟原版地图的亮度档（{@code MapItem} 里 {@code d3 > 0.6 → HIGH}）同一条思路：
     * 只看相邻格高度差，不看绝对高度——绝对高度那部分由 {@link #applyShade} 负责。
     * 原版比较的是「本格 − 北邻格」，这里再加上西邻格。本格高于西北邻格 = 坡面朝西北
     * （迎光）→ 提亮；本格低于西北邻格 = 背光 → 压暗。
     *
     * @param height      本格平均高度
     * @param westHeight  西侧格平均高度，未知传 {@link #NO_HEIGHT}
     * @param northHeight 北侧格平均高度，未知传 {@link #NO_HEIGHT}
     * @param step        一格/一个 texel 覆盖的方块数（LOD 与小地图 bpp）：坡降按格距归一，
     *                    缩小时同样的山不会越来越黑
     * @param strength    0 = 关，100 = 默认（一格坡降约 ±15%），200 = 最重
     */
    public static int applyRelief(int argb, int height, int westHeight, int northHeight,
                                  int step, int strength) {
        if (strength <= 0 || height == NO_HEIGHT) {
            return argb;
        }
        int dx = westHeight == NO_HEIGHT ? 0 : height - westHeight;
        int dz = northHeight == NO_HEIGHT ? 0 : height - northHeight;
        double slope = (dx + dz) * 0.5 / Math.max(1, step);
        double factor = 1.0 + 0.22 * (strength / 100.0) * Math.tanh(0.8 * slope);
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((argb & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * ARGB → NativeImage 的 ABGR 内存布局。
     * <p>小端存储后字节序为 R,G,B,A，正好是 GPU 的 RGBA8 纹理格式。
     * <p>1.21.1 的 {@code NativeImage.setPixelRGBA} 实际是直存内存 int（不做 RGBA→ABGR 转换），
     * 传 RGBA 会让 R 通道被填成 alpha、整体偏红（实测），所以这里按 ABGR 传。
     */
    public static int toNativeAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
