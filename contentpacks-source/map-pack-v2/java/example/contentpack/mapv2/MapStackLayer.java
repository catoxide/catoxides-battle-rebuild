package example.contentpack.mapv2;

import java.util.ArrayList;
import java.util.List;

/**
 * 表面栈里的一层：一个方块「从正上方看到的那一面」贴在什么位置、什么颜色、多透。
 *
 * <p>{@code u/v} 是贴图子矩形，按 {@link #UV_UNITS}（64）等分 —— 床这类整张 64×64
 * 的实体贴图只有 64 等分能落到像素；{@code x/z} 是这一面在<b>旋转之前</b>的格子内
 * 投影范围，按 {@link #UNITS}（16）等分。火把只占中间 2×2，台阶占满 16×16 但可能
 * 分成两段 —— 这样不完整的方块按它真实的顶面覆盖来画，而不是被拉伸成一整格。
 * 渲染时先按这个本地矩形铺贴图，再把整个格子绕中心顺时针转 {@code rotation} 档；
 * 45°、22.5° 的斜向告示牌只有「本地矩形 + 角度」表达得了。
 *
 * <p>{@code count} 是连续若干层「贴图、位置、方块都一样」的叠加数（树叶、同色玻璃）：
 * 存储时合并成一层，平色按 {@link #stackedAlpha} 叠、再按 {@link #density} 压暗，
 * 放大画纹理时也用它们把层数画得出来。
 *
 * @param y  这一面所在方块的世界 y（层数合并后取最上面那层）
 * @param argb 这一层自己的平色（已含群系 tint），只用于低缩放混色与高缩放调色；
 *             {@code sprite} 为空时（平色层）它就是这一面的颜色
 * @param alpha 贴图子矩形的平均不透明度（0..255）
 * @param rotation 贴图相对格子的旋转，单位 22.5°（0..15，顺时针）：铁轨的走向、
 *                 中继器的朝向、告示牌的 45° 斜放都在这里，位置和 UV 子矩形本身
 *                 表达不了。只有 90° 整档的层（烘焙四边形、旧瓦片）是 4 的倍数
 * @param sprite 图集贴图 ID；空字符串表示这一层没有贴图，只画 {@code argb} 的矩形
 *               （生物头这类贴图不在图集里的方块）
 */
public record MapStackLayer(String sprite, int u0, int v0, int u1, int v1,
                            int x0, int z0, int x1, int z1,
                            String blockId, int y, int argb, int alpha, int count,
                            int rotation) {

    /** 格子内的投影坐标单位数。 */
    public static final int UNITS = 16;
    /** 贴图子矩形的坐标单位数（像素级：床贴图是 64×64）。 */
    public static final int UV_UNITS = 64;
    /** 一层最多代表的叠加数；再多视觉上已经分不出来。 */
    public static final int MAX_COUNT = 15;

    public MapStackLayer {
        if (sprite == null) {
            throw new IllegalArgumentException("stack layer needs a sprite id (empty = flat color)");
        }
        if (blockId == null) {
            throw new IllegalArgumentException("stack layer needs a block id");
        }
        if (rotation < 0 || rotation > 15) {
            throw new IllegalArgumentException("stack rotation must be 0..15: " + rotation);
        }
    }

    public boolean fullFootprint() {
        return x0 == 0 && z0 == 0 && x1 == UNITS && z1 == UNITS;
    }

    public boolean fullUv() {
        return u0 == 0 && v0 == 0 && u1 == UV_UNITS && v1 == UV_UNITS;
    }

    public int area() {
        return Math.max(0, x1 - x0) * Math.max(0, z1 - z0);
    }

    /** 这一面占整格的比例（0..1）。 */
    public float coverage() {
        return area() / (float) (UNITS * UNITS);
    }

    /** 贴图、位置和旋转都一样 —— 相邻两层可以合并计数。 */
    public boolean sameVisual(MapStackLayer other) {
        return sprite.equals(other.sprite)
                && u0 == other.u0 && v0 == other.v0 && u1 == other.u1 && v1 == other.v1
                && x0 == other.x0 && z0 == other.z0 && x1 == other.x1 && z1 == other.z1
                && blockId.equals(other.blockId) && rotation == other.rotation;
    }

    public MapStackLayer withCount(int newCount) {
        int clamped = Math.max(1, Math.min(MAX_COUNT, newCount));
        if (clamped == count) {
            return this;
        }
        return new MapStackLayer(sprite, u0, v0, u1, v1, x0, z0, x1, z1,
                blockId, y, argb, alpha, clamped, rotation);
    }

    /**
     * 格内本地坐标（0..16）是否落在这一层上：把点绕格心逆着转回去，再比本地矩形。
     * 与渲染端「按本地矩形铺图 + 绕格心顺时针转 {@code rotation × 22.5°}」严格互逆，
     * 所以指针命中的层就是屏幕上那一像素画出来的层。
     */
    public boolean covers(double localX, double localZ) {
        double dx = localX - UNITS / 2.0;
        double dz = localZ - UNITS / 2.0;
        if (rotation != 0) {
            double theta = Math.toRadians(rotation * 22.5);
            double cos = Math.cos(theta);
            double sin = Math.sin(theta);
            double localRotatedX = dx * cos + dz * sin;
            dz = -dx * sin + dz * cos;
            dx = localRotatedX;
        }
        double x = UNITS / 2.0 + dx;
        double z = UNITS / 2.0 + dz;
        return x >= x0 && x < x1 && z >= z0 && z < z1;
    }

    /**
     * 世界矩形 → 旋转之前的本地矩形，只对 90° 整档（{@code quarters} 0..3）成立。
     *
     * <p>烘焙四边形和旧瓦片只有 90° 旋转，采集时用这一步把世界矩形折回本地；
     * 合成顶面（告示牌、床、箱子）本来就按模型空间给本地矩形，不经过这里。
     * 任意 22.5° 档的本地矩形无法从世界矩形唯一还原，所以不存在对应逆函数。
     */
    public static int[] unrotatedRect(int quarters, int x0, int z0, int x1, int z1) {
        return switch (quarters & 3) {
            case 1 -> new int[]{z0, UNITS - x1, z1, UNITS - x0};
            case 2 -> new int[]{UNITS - x1, UNITS - z1, UNITS - x0, UNITS - z0};
            case 3 -> new int[]{UNITS - z1, x0, UNITS - z0, x1};
            default -> new int[]{x0, z0, x1, z1};
        };
    }

    /**
     * 把「上 → 下」的相邻同形层合并成一层 + 计数，例如四层树叶变成一层 ×4。
     * 返回的列表仍是「上 → 下」。
     */
    public static List<MapStackLayer> merge(List<MapStackLayer> topToBottom) {
        List<MapStackLayer> merged = new ArrayList<>(topToBottom.size());
        for (MapStackLayer layer : topToBottom) {
            int last = merged.size() - 1;
            if (last >= 0 && merged.get(last).sameVisual(layer)) {
                MapStackLayer previous = merged.get(last);
                merged.set(last, previous.withCount(previous.count() + layer.count()));
            } else {
                merged.add(layer);
            }
        }
        return merged;
    }

    /** 叠了 {@code count} 层、每层不透明度 {@code alpha} 后的等效不透明度。 */
    public static float stackedAlpha(int alpha, int count) {
        float single = Math.max(0f, Math.min(1f, alpha / 255f));
        if (count <= 1) {
            return single;
        }
        return 1f - (float) Math.pow(1f - single, count);
    }

    /** 层数越多越暗（每层 10%）；到第 4 层封顶，不把树冠压成黑块。 */
    public static float density(int count) {
        return Math.max(0.72f, 1f - 0.10f * Math.max(0, count - 1));
    }

    /**
     * 记录色 ÷ 贴图子矩形均值 → 渲染顶点色（任一分量 &gt; 1 时整体等比压暗）。
     *
     * <p>记录色就是这一面的平均色时比值正好是 1，贴图原样画出；记录色要是
     * {@link net.minecraft.world.level.material.MapColor} 这类分类色，比值就变成
     * 「分类色 ÷ 贴图」的 tint —— {@code MapColor.PLANT} 会把粉色花簇乘成荧光绿。
     *
     * @param red/green/blue 记录色（已做高度阴影）
     * @param avgRed/avgGreen/avgBlue 贴图子矩形的 alpha 加权均值（{@code BlockSurfaces.avgColor}）
     */
    public static float[] vertexTint(float red, float green, float blue,
                                     float avgRed, float avgGreen, float avgBlue) {
        float r = red / avgRed;
        float g = green / avgGreen;
        float b = blue / avgBlue;
        float peak = Math.max(r, Math.max(g, b));
        if (peak > 1f) {
            r /= peak;
            g /= peak;
            b /= peak;
        }
        return new float[]{Math.max(0f, Math.min(1f, r)),
                Math.max(0f, Math.min(1f, g)),
                Math.max(0f, Math.min(1f, b))};
    }

    /**
     * 平色（缩略图/低缩放）的表面在栈里的位置：最上面一层流体（水/岩浆），
     * 或平均读数 ≥ 0.5 的层（树叶、冰这类读起来不透明的 cutout）。
     *
     * <p>只占一角的顶面（火把、栏杆、按钮）不当表面：低缩放一格只有一个像素，
     * 让它盖掉下面的地面比留出地面更不像；它们只按覆盖比例参与上面的混色。
     * 更深的层不参与平色混色；返回 {@code -1} 表示用终点色。
     */
    public static int flatBaseIndex(List<MapStackLayer> topToBottom) {
        for (int i = 0; i < topToBottom.size(); i++) {
            MapStackLayer layer = topToBottom.get(i);
            if (layer.coverage() < 0.5f) {
                continue;
            }
            if (FluidSurfaces.isFluidLayer(layer.blockId()) || layer.alpha() >= 128) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 栈顶连续「同一方块、同一高度」的层数：一个多部件模型拼出来的全部顶面算一组
     * （粉色花簇是四片 8×8 分占四角）。
     *
     * <p>低缩放判断「这一块是不是表面」必须按整组看：单片只占四分之一格，
     * 永远够不到 {@link #flatBaseIndex} 的覆盖门槛。
     */
    public static int topGroupEnd(List<MapStackLayer> topToBottom) {
        if (topToBottom.isEmpty()) {
            return 0;
        }
        MapStackLayer top = topToBottom.get(0);
        int end = 1;
        while (end < topToBottom.size()) {
            MapStackLayer layer = topToBottom.get(end);
            if (layer.y() != top.y() || !layer.blockId().equals(top.blockId())) {
                break;
            }
            end++;
        }
        return end;
    }

    /** 前 {@code end} 层在格内的投影并集覆盖格数（0..256）；叠在一起的矩形不重复计。 */
    public static int unionCoverage(List<MapStackLayer> topToBottom, int end) {
        boolean[] mask = new boolean[UNITS * UNITS];
        int covered = 0;
        for (int i = 0; i < end && i < topToBottom.size(); i++) {
            MapStackLayer layer = topToBottom.get(i);
            int x0 = Math.max(0, layer.x0());
            int x1 = Math.min(UNITS, layer.x1());
            int z0 = Math.max(0, layer.z0());
            int z1 = Math.min(UNITS, layer.z1());
            for (int z = z0; z < z1; z++) {
                for (int x = x0; x < x1; x++) {
                    int cell = z * UNITS + x;
                    if (!mask[cell]) {
                        mask[cell] = true;
                        covered++;
                    }
                }
            }
        }
        return covered;
    }

    /** 前 {@code end} 层按投影面积加权的平均色（24 位 RGB）；面积为 0 时返回 0。 */
    public static int averageArgb(List<MapStackLayer> topToBottom, int end) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long weight = 0;
        for (int i = 0; i < end && i < topToBottom.size(); i++) {
            MapStackLayer layer = topToBottom.get(i);
            long area = layer.area();
            if (area <= 0) {
                continue;
            }
            red += ((layer.argb() >> 16) & 0xFF) * area;
            green += ((layer.argb() >> 8) & 0xFF) * area;
            blue += (layer.argb() & 0xFF) * area;
            weight += area;
        }
        if (weight == 0) {
            return 0;
        }
        return (int) (red / weight) << 16 | (int) (green / weight) << 8 | (int) (blue / weight);
    }
}
