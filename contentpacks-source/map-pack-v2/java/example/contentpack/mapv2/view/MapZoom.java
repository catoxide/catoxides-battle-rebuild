package example.contentpack.mapv2.view;

/**
 * 缩放档位与 LOD 选择（纯算术）。
 *
 * <p>坐标系是<b>画布像素</b>（1 画布像素 = 显示器物理像素），不是 Minecraft 的 GUI 像素。
 * {@code bpp} = 每个画布像素覆盖的方块数：{@code bpp = 2} 表示缩得很小，{@code 1/32} 表示
 * 一个方块铺 32 个像素。
 *
 * <p>档位全是 2 的幂，于是两边形状都很好：
 * <ul>
 *   <li>{@code bpp <= 1}：{@code 1/bpp} 是整数 ⇒ 方块边界落在整像素上（配合
 *       {@link MapProjection} 的原点吸附）。</li>
 *   <li>{@code bpp >= 1}：{@code lod = log2(bpp)} ⇒ {@code lodSize * bpp == 512}，
 *       一个 texel 正好一个画布像素，纹理不需要任何重采样就被拉伸到位
 *       （整数倍放大 + 最近邻 = 无损）。</li>
 * </ul>
 */
public final class MapZoom {

    /** 一块瓦片覆盖的方块边长。 */
    public static final int TILE_BLOCKS = 512;
    /** LOD 级数：{@code 512 >> 5 == 16}，最粗一级一个 texel 覆盖 32 个方块。 */
    public static final int MAX_LOD = 5;
    /** 最深档：一个像素 1/64 个方块（一个方块 64 像素）。 */
    public static final double MIN_BPP = 1.0 / 64.0;
    /** 最浅档：一个像素 32 个方块（512 的瓦片只剩 16 像素）。 */
    public static final double MAX_BPP = 32.0;
    /** 每方块至少这么多画布像素才值得铺方块顶面贴图（16×16 贴图刚好 1:1）。 */
    public static final double BLOCK_TOP_BPP = 1.0 / 16.0;

    private static final double[] STEPS = {
            1.0 / 64, 1.0 / 32, 1.0 / 16, 1.0 / 8, 1.0 / 4, 1.0 / 2,
            1, 2, 4, 8, 16, 32
    };

    private MapZoom() {
    }

    public static double[] steps() {
        return STEPS.clone();
    }

    /** 放大一档（bpp 变小）；已经在最深档时不动。非档位值取最近的更浅档。 */
    public static double stepIn(double bpp) {
        for (int i = STEPS.length - 1; i >= 0; i--) {
            if (STEPS[i] < bpp * (1.0 - 1e-9)) {
                return STEPS[i];
            }
        }
        return STEPS[0];
    }

    /** 缩小一档（bpp 变大）；已经在最浅档时不动。非档位值取最近的更深档。 */
    public static double stepOut(double bpp) {
        for (double step : STEPS) {
            if (step > bpp * (1.0 + 1e-9)) {
                return step;
            }
        }
        return STEPS[STEPS.length - 1];
    }

    /**
     * 该缩放档位的 LOD 级数。
     *
     * <p>{@code bpp >= 1} 时取 {@code floor(log2(bpp))}，保证
     * {@code (TILE_BLOCKS >> lod) * bpp == TILE_BLOCKS}（档位为 2 的幂时精确成立）。
     * 放大档一律用 LOD 0：深处要的是原始像素，任何降采样都会丢细节。
     */
    public static int lod(double bpp) {
        if (!(bpp > 1.0)) {
            return 0;
        }
        int lod = 0;
        while (lod < MAX_LOD && (1L << (lod + 1)) <= Math.round(bpp)) {
            lod++;
        }
        return lod;
    }

    /** 该 LOD 的纹理边长（texel）。 */
    public static int lodTextureSize(int lod) {
        return TILE_BLOCKS >> Math.max(0, Math.min(MAX_LOD, lod));
    }

    /** 一块瓦片在画布上占的像素边长；档位为 2 的幂时一定是整数。 */
    public static int tilePixels(double bpp) {
        return (int) Math.round(TILE_BLOCKS / bpp);
    }

    /** 是否深到该铺方块顶面贴图。 */
    public static boolean blockTops(double bpp) {
        return bpp <= BLOCK_TOP_BPP;
    }
}
