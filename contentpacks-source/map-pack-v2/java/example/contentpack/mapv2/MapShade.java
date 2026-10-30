package example.contentpack.mapv2;

/**
 * 像素着色工具（地图瓦片生成用）。
 *
 * <p>从旧包 {@code MapMinimap} 里抽出来：瓦片纹理和方块顶面都调它，和小地图本身无关。
 */
public final class MapShade {

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
