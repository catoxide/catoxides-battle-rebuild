package example.contentpack.mapv2.client;

import com.mojang.blaze3d.platform.NativeImage;
import example.contentpack.mapv2.MapStackLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个方块「从正上方看」贡献了哪些面、这些面在格子里占多大、有多不透明。
 *
 * <p>判据看烘焙模型里法向朝上的面（自己按顶点算法向的 y 分量，见 {@link #upwardNormal}）：
 * <ul>
 *   <li>十字模型（草、花）没有朝上的面 → {@link Kind#NONE}，从上面看是空的；</li>
 *   <li>顶面占满整格且不透明（石头、草方块、台阶、雪层、地毯）→ {@link Kind#TERMINAL}，
 *       采集停在这里；</li>
 *   <li>其余（玻璃、冰、树叶的镂空、火把的 2×2、拉杆、栏杆、按钮、铁栏杆）→
 *       {@link Kind#LAYER}，作为叠加层记下来后继续往下找终点。</li>
 * </ul>
 *
 * <p>不能只查 {@code getQuads(state, UP, …)}：那只返回「以 up 为剔除面」的面。火把、拉杆、
 * 按钮、栏杆、台阶下半的顶面都没有 cullface，落在不剔除列表里，必须一起拿再按面朝向筛 ——
 * 否则这些方块会被当成「从上面看不见」直接跳过。
 *
 * <p>必须用真实 {@link BlockState}：拉杆、按钮、原木、楼梯的方向都在 state 里，
 * 用 {@code defaultBlockState()} 会取错顶面。
 *
 * <p>只接「从正上方看是一块平铺矩形」的面：法向朝上只是必要条件，投影多边形面积还得
 * 达到包围盒的 {@link #AXIS_ALIGNED_RATIO}。上坡铁轨经过 45° 元素旋转 + rescale 后
 * XZ 投影正好是整格，可以按顶面画；壁挂紫晶芽的十字斜面投影成一条斜带
 * （面积比 ≈0.61），竖直面（竖直铁链、十字模型）投影成线段，按轴对齐框铺贴图只会
 * 得到一条错方向的色带。
 *
 * <p>贴图与模型在资源重载时更换，缓存必须跟着清（{@link #invalidate()}）。
 * 只在客户端主线程调用。
 */
public final class BlockSurfaces {

    /** 顶面占满整格、面积平均不透明度达到这个值才算终点。 */
    private static final float OPAQUE_THRESHOLD = 0.95f;
    /** 平均不透明度低于这个值的面等于看不见，不记层。 */
    private static final float VISIBLE_THRESHOLD = 0.02f;
    /** 投影坐标（格内位置）按格子十六等分存。 */
    private static final int UNITS = 16;
    /**
     * 贴图子矩形按六十四等分存。16 像素的方块贴图两种精度一样，但床这类整张
     * 64×64 的实体贴图只有 64 等分才能落到像素（床头顶面在 v 6..22）。
     */
    public static final int UV_UNITS = 64;
    /**
     * XZ 投影多边形面积 / 包围盒面积的最低比例：1 = 轴对齐矩形。竖直面投影成线段（≈0），
     * 45° 斜着的十字模型（壁挂紫晶芽）约 0.61；上坡铁轨、横放的铁链投影仍是轴对齐矩形。
     */
    private static final float AXIS_ALIGNED_RATIO = 0.8f;
    /**
     * 法向 y 分量的下限：水平顶面 1，45° 斜面 ≈0.707，竖直面 0，元素底面 −0.707。
     * 只用来分「朝上 / 不朝上」，角度更刁的斜带交给投影面积比例筛。
     */
    private static final float MIN_UP_NORMAL = 0.1f;
    /** 四个旋转档位下贴图 +u / +v 在格子里的方向（x 向东、z 向南，90° 顺时针一档）。 */
    private static final float[][] TARGET_U = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    private static final float[][] TARGET_V = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
    /** 会持有瓦片贴图的图集：方块 + 实体渲染方块各自的图集。 */
    private static final ResourceLocation[] ENTITY_ATLASES = {
            TextureAtlas.LOCATION_BLOCKS, Sheets.BED_SHEET, Sheets.SIGN_SHEET, Sheets.CHEST_SHEET,
            Sheets.SHULKER_SHEET, Sheets.DECORATED_POT_SHEET};

    public enum Kind {
        /** 从上面看不到东西（无 UP 面 / 全透明 / 不渲染）。 */
        NONE,
        /** 能看穿或盖不满：记成一层，继续往下。 */
        LAYER,
        /** 顶面完整且不透明：采集的终点。 */
        TERMINAL
    }

    /**
     * 一片朝上的面。
     *
     * @param u0/v0/u1/v1 贴图内的六十四等分范围（0..64，见 {@link #UV_UNITS}）
     * @param x0/z0/x1/z1 这一面在<b>旋转之前</b>的格子内投影范围（0..16）
     * @param alpha       这片贴图的平均不透明度（0..255）
     * @param height      这一面在格子里的高度（0..16，十六等分；上坡铁轨可达 17）：同一格有几个顶面时按它排序
     * @param rotation    贴图相对格子的旋转（0..15，每档 22.5° 顺时针）：铁轨的走向、
     *                    中继器的朝向、告示牌的斜放都在这里。烘焙四边形只会给出 4 的倍数
     * @param tintIndex   模型里这一面的 {@code tintindex}（没写是 -1）：只有带 tint 的面才套
     *                    {@code BlockColors}，炼药锅的水/岩浆内容物就是靠它和锅沿分开
     * @param flatArgb    24 位平色；{@code -1} 表示按 {@code sprite} 取色，非负表示没有贴图的
     *                    平色面（生物头：贴图不在图集里，只记头顶的平均色）
     */
    public record Part(@Nullable TextureAtlasSprite sprite, int u0, int v0, int u1, int v1,
                       int x0, int z0, int x1, int z1, int alpha, float height,
                       int rotation, int tintIndex, int flatArgb) {

        /** 贴图面：颜色由贴图决定。 */
        public Part(TextureAtlasSprite sprite, int u0, int v0, int u1, int v1,
                    int x0, int z0, int x1, int z1, int alpha, float height,
                    int rotation, int tintIndex) {
            this(sprite, u0, v0, u1, v1, x0, z0, x1, z1, alpha, height, rotation, tintIndex, -1);
        }

        /** 没有贴图的平色面（生物头）。 */
        public static Part flat(int argb, int x0, int z0, int x1, int z1, float height) {
            return new Part(null, 0, 0, 0, 0, x0, z0, x1, z1, 255, height, 0, -1,
                    argb & 0xFFFFFF);
        }

        public int area() {
            return Math.max(0, x1 - x0) * Math.max(0, z1 - z0);
        }
    }

    public record Surface(Kind kind, List<Part> parts, int coverage, float alpha) {
    }

    /** 没进世界 / 取不到模型：当作实心，保住整列的颜色，不让采集断掉。 */
    private static final Surface UNKNOWN = new Surface(Kind.TERMINAL, List.of(), 0, 1f);
    private static final Surface NOTHING = new Surface(Kind.NONE, List.of(), 0, 0f);

    private static final Map<BlockState, Surface> CACHE = new HashMap<>();
    private static final Map<BlockState, Integer> TEXTURE_COLOR = new HashMap<>();
    private static final Map<String, float[]> AVG_COLOR = new HashMap<>();
    private static final Map<String, Integer> RAW_TEXTURE_COLOR = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create(0L);

    private BlockSurfaces() {
    }

    public static Surface of(BlockState state) {
        Surface cached = CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        Surface resolved = resolve(state);
        CACHE.put(state, resolved);
        return resolved;
    }

    private static Surface resolve(BlockState state) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return UNKNOWN;
        }
        RenderShape shape = state.getRenderShape();
        if (shape == RenderShape.INVISIBLE) {
            return NOTHING;
        }
        if (shape == RenderShape.ENTITYBLOCK_ANIMATED) {
            // 箱子/告示牌之类没有烘焙模型；当实心，颜色留给平色层
            return UNKNOWN;
        }
        BakedModel model;
        List<BakedQuad> quads;
        try {
            model = minecraft.getBlockRenderer().getBlockModel(state);
            quads = upFaces(model, state);
        } catch (RuntimeException ex) {
            return UNKNOWN;
        }
        if (quads.isEmpty()) {
            return NOTHING;
        }
        List<Part> parts = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            Part part = partOf(quad);
            if (part != null) {
                parts.add(part);
            }
        }
        return surfaceOfParts(parts);
    }

    /**
     * 一组顶面 → 表面汇总。给没有方块烘焙模型的方块用（床靠 {@code BedSurfaces}
     * 直接拼出顶面），判据与 {@link #resolve} 完全一致。
     */
    public static Surface surfaceOfParts(List<Part> parts) {
        if (parts.isEmpty()) {
            return NOTHING;
        }
        boolean[] mask = new boolean[UNITS * UNITS];
        long alphaWeighted = 0;
        long areaSum = 0;
        for (Part part : parts) {
            int area = part.area();
            if (area <= 0) {
                continue;
            }
            for (int z = part.z0(); z < part.z1(); z++) {
                for (int x = part.x0(); x < part.x1(); x++) {
                    mask[z * UNITS + x] = true;
                }
            }
            alphaWeighted += (long) part.alpha() * area;
            areaSum += area;
        }
        if (areaSum == 0) {
            return NOTHING;
        }
        // 同一格可能有好几个顶面（台阶、栏杆、火把底座）：高的画在最后，从高到低排
        List<Part> sorted = new ArrayList<>(parts);
        sorted.sort(Comparator.comparingDouble(Part::height).reversed());
        int coverage = 0;
        for (boolean covered : mask) {
            if (covered) {
                coverage++;
            }
        }
        float alpha = alphaWeighted / (areaSum * 255f);
        Kind kind = coverage >= UNITS * UNITS && alpha >= OPAQUE_THRESHOLD
                ? Kind.TERMINAL : Kind.LAYER;
        return new Surface(kind, List.copyOf(sorted), coverage, alpha);
    }

    /** 这个方块有没有带 {@code tintindex} 的顶面（炼药锅的内容物、草方块的上表面）。 */
    public static boolean hasTintedParts(BlockState state) {
        for (Part part : of(state).parts()) {
            if (part.tintIndex() >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 模型里所有朝上的面。
     *
     * <p>{@code getQuads(state, UP, …)} 只给「以 up 为剔除面」的面 —— 火把、拉杆、按钮、
     * 栏杆、台阶下半的顶面没有 cullface，只能在 {@code getQuads(state, null, …)} 里拿到，
     * 两个列表都要收。是否朝上看几何法向（{@link #upwardNormal}），不看
     * {@link BakedQuad#getDirection()}。
     */
    private static List<BakedQuad> upFaces(BakedModel model, BlockState state) {
        List<BakedQuad> faces = new ArrayList<>();
        collectUpFaces(model.getQuads(state, Direction.UP, RANDOM, ModelData.EMPTY, null), faces);
        collectUpFaces(model.getQuads(state, null, RANDOM, ModelData.EMPTY, null), faces);
        return faces;
    }

    private static void collectUpFaces(List<BakedQuad> quads, List<BakedQuad> out) {
        for (BakedQuad quad : quads) {
            if (acceptsTop(quad)) {
                out.add(quad);
            }
        }
    }

    /** 这一片够朝上吗：45° 斜面 ≈0.707，竖直面 0，元素底面 −0.707。 */
    static boolean acceptsTop(BakedQuad quad) {
        return upwardNormal(quad) >= MIN_UP_NORMAL;
    }

    /**
     * 四边形法向的 y 分量（-1..1），按顶点算（同 {@code FaceBakery.calculateFacing} 的叉积）。
     *
     * <p>不能信 {@link BakedQuad#getDirection()}：45° 面上它和水平方向严格平票 ——
     * UP 与 NORTH/SOUTH 的点积都是 0.7071068，而原版用「严格大于」比较，
     * 浮点误差让上坡铁轨（±45° 元素旋转）全部判成 SOUTH/NORTH/WEST/EAST。
     * 按 {@code == UP} 筛会把四种上坡朝向整片丢掉。
     */
    private static float upwardNormal(BakedQuad quad) {
        int[] v = quad.getVertices();
        float ax = Float.intBitsToFloat(v[16]) - Float.intBitsToFloat(v[8]);
        float ay = Float.intBitsToFloat(v[17]) - Float.intBitsToFloat(v[9]);
        float az = Float.intBitsToFloat(v[18]) - Float.intBitsToFloat(v[10]);
        float bx = Float.intBitsToFloat(v[0]) - Float.intBitsToFloat(v[8]);
        float by = Float.intBitsToFloat(v[1]) - Float.intBitsToFloat(v[9]);
        float bz = Float.intBitsToFloat(v[2]) - Float.intBitsToFloat(v[10]);
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return length < 1e-6f ? 0f : ny / length;
    }

    /** 一片朝上的面 → 贴图子矩形 + 格子内投影 + 贴图旋转；整片透明时返回 {@code null}。 */
    @Nullable
    private static Part partOf(BakedQuad quad) {
        TextureAtlasSprite sprite = quad.getSprite();
        float su0 = sprite.getU0();
        float su1 = sprite.getU1();
        float sv0 = sprite.getV0();
        float sv1 = sprite.getV1();
        float du = su1 - su0;
        float dv = sv1 - sv0;
        if (du <= 0f || dv <= 0f) {
            return null;
        }
        int[] vertices = quad.getVertices();
        float[] px = new float[4];
        float[] pz = new float[4];
        float[] pu = new float[4];
        float[] pv = new float[4];
        float minX = UNITS;
        float maxX = 0f;
        float minZ = UNITS;
        float maxZ = 0f;
        float maxY = 0f;
        float minU = 1f;
        float maxU = 0f;
        float minV = 1f;
        float maxV = 0f;
        for (int i = 0; i < 4; i++) {
            int base = i * 8;
            float x = Float.intBitsToFloat(vertices[base]) * UNITS;
            float z = Float.intBitsToFloat(vertices[base + 2]) * UNITS;
            // 烘焙时 UV 已换算成图集坐标（FaceBakery 调 sprite.getU/getV），这里折回贴图内比例
            float u = Float.intBitsToFloat(vertices[base + 4]);
            float v = Float.intBitsToFloat(vertices[base + 5]);
            px[i] = x;
            pz[i] = z;
            pu[i] = u;
            pv[i] = v;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
            // 斜面（上坡铁轨）也收：高度只用来排序，不再用来排除
            maxY = Math.max(maxY, Float.intBitsToFloat(vertices[base + 1]) * UNITS);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        int x0 = clamp(Math.round(minX), 0, UNITS);
        int x1 = clamp(Math.round(maxX), 0, UNITS);
        int z0 = clamp(Math.round(minZ), 0, UNITS);
        int z1 = clamp(Math.round(maxZ), 0, UNITS);
        if (x1 <= x0 || z1 <= z0) {
            return null;
        }
        // 从正上方看必须是一块平铺的矩形：竖直面投影成线段（竖直铁链、紫晶芽的十字模型），
        // 斜 45° 的十字模型投影成一条斜带 —— 按轴对齐框铺贴图只会得到一条错方向的色带。
        // 上坡铁轨、横放的铁链经各自的旋转后投影仍是轴对齐矩形，都能收。
        float box = (maxX - minX) * (maxZ - minZ);
        if (box <= 0f || polygonArea(px, pz) < box * AXIS_ALIGNED_RATIO) {
            return null;
        }
        float fu0 = clamp01((minU - su0) / du);
        float fu1 = clamp01((maxU - su0) / du);
        float fv0 = clamp01((minV - sv0) / dv);
        float fv1 = clamp01((maxV - sv0) / dv);
        int u0 = Math.round(fu0 * UV_UNITS);
        int u1 = Math.round(fu1 * UV_UNITS);
        int v0 = Math.round(fv0 * UV_UNITS);
        int v1 = Math.round(fv1 * UV_UNITS);
        if (u1 <= u0 || v1 <= v0) {
            // 退化 UV（少数模型只看贴图一个像素）：整张贴图铺满这一面
            u0 = 0;
            v0 = 0;
            u1 = UV_UNITS;
            v1 = UV_UNITS;
        }
        int alpha = spriteAlpha(sprite, fu0, fv0, fu1, fv1);
        if (alpha < Math.round(VISIBLE_THRESHOLD * 255f)) {
            return null;
        }
        int quarters = rotationOf(px, pz, pu, pv);
        // 瓦片里存的是旋转之前的本地矩形：渲染端只负责铺贴图 + 转位姿。
        // 烘焙四边形只有 90° 整档，世界矩形能唯一折回本地。
        int[] local = MapStackLayer.unrotatedRect(quarters, x0, z0, x1, z1);
        return new Part(sprite, u0, v0, u1, v1, local[0], local[1], local[2], local[3],
                alpha, maxY, quarters * 4, quad.getTintIndex());
    }

    /** 四边形在 XZ 平面上的面积（鞋带公式，顶点按烘焙顺序）。 */
    private static float polygonArea(float[] px, float[] pz) {
        float twice = 0f;
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) & 3;
            twice += px[i] * pz[next] - px[next] * pz[i];
        }
        return Math.abs(twice) * 0.5f;
    }

    /**
     * 贴图要顺时针转几个 90°：按四个角的 UV-位置对应拟合出 +u / +v 在格子里指向哪，
     * 再和四个档位的目标方向比对，取最合的那个。
     *
     * <p>blockstate 的 y 旋转只改顶点位置、不改 UV 值，所以铁轨的走向、中继器的朝向
     * 只在这个对应关系里；位置和多边形本身都看不出来。UV 镜像（铁链的 [3,0,0,16]）
     * 归一化时已经丢了，这里按最接近的旋转处理。
     */
    private static int rotationOf(float[] px, float[] pz, float[] pu, float[] pv) {
        float[] gu = uvGradient(px, pz, pu);
        float[] gv = uvGradient(px, pz, pv);
        if (gu == null || gv == null) {
            return 0;
        }
        float lengthU = (float) Math.hypot(gu[0], gu[1]);
        float lengthV = (float) Math.hypot(gv[0], gv[1]);
        if (lengthU < 1e-6f || lengthV < 1e-6f) {
            return 0;
        }
        gu[0] /= lengthU;
        gu[1] /= lengthU;
        gv[0] /= lengthV;
        gv[1] /= lengthV;
        // 贴图里占得多的轴更重要：铁链的贴图是 3×16，把 16 像素的长边转错方向最显眼，
        // 而横放的铁链那 3 像素宽的轴本来就是斜的，匹配不上也不该抢方向
        float spanU = span(pu);
        float spanV = span(pv);
        int best = 0;
        float bestScore = -Float.MAX_VALUE;
        for (int r = 0; r < 4; r++) {
            float score = spanU * (gu[0] * TARGET_U[r][0] + gu[1] * TARGET_U[r][1])
                    + spanV * (gv[0] * TARGET_V[r][0] + gv[1] * TARGET_V[r][1]);
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    private static float span(float[] values) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return Math.max(0f, max - min);
    }

    /** ∂value/∂x、∂value/∂z 的最小二乘拟合；投影退化（四点共线）时返回 {@code null}。 */
    @Nullable
    private static float[] uvGradient(float[] px, float[] pz, float[] values) {
        float meanX = 0f;
        float meanZ = 0f;
        float meanValue = 0f;
        for (int i = 0; i < 4; i++) {
            meanX += px[i];
            meanZ += pz[i];
            meanValue += values[i];
        }
        meanX /= 4f;
        meanZ /= 4f;
        meanValue /= 4f;
        double a = 0;
        double b = 0;
        double d = 0;
        double p = 0;
        double q = 0;
        for (int i = 0; i < 4; i++) {
            double dx = px[i] - meanX;
            double dz = pz[i] - meanZ;
            double dv = values[i] - meanValue;
            a += dx * dx;
            b += dx * dz;
            d += dz * dz;
            p += dx * dv;
            q += dz * dv;
        }
        double det = a * d - b * b;
        if (a <= 0 || d <= 0 || det <= 1e-6 * a * d) {
            return null;
        }
        return new float[]{(float) ((d * p - b * q) / det), (float) ((a * q - b * p) / det)};
    }

    private static int spriteAlpha(TextureAtlasSprite sprite, float u0, float v0,
                                   float u1, float v1) {
        int width = Math.max(1, sprite.contents().width());
        int height = Math.max(1, sprite.contents().height());
        int px0 = clamp(Math.round(u0 * width), 0, width - 1);
        int px1 = clamp(Math.round(u1 * width), px0 + 1, width);
        int py0 = clamp(Math.round(v0 * height), 0, height - 1);
        int py1 = clamp(Math.round(v1 * height), py0 + 1, height);
        long sum = 0;
        int count = 0;
        for (int y = py0; y < py1; y++) {
            for (int x = px0; x < px1; x++) {
                sum += (sprite.getPixelRGBA(0, x, y) >>> 24) & 0xFF;
                count++;
            }
        }
        return count == 0 ? 0 : Math.round(sum / (float) count);
    }

    /**
     * 贴图子矩形的平均色（alpha 加权，0..1）；渲染时用它把「瓦片颜色 ÷ 贴图均值」
     * 变成顶点色，让群系 tint 和高度明暗都保留。缓存按贴图 + 子矩形。
     */
    public static float[] avgColor(TextureAtlasSprite sprite, int u0, int v0, int u1, int v1) {
        String key = sprite.contents().name() + "@" + u0 + "," + v0 + "," + u1 + "," + v1;
        float[] cached = AVG_COLOR.get(key);
        if (cached != null) {
            return cached;
        }
        int width = Math.max(1, sprite.contents().width());
        int height = Math.max(1, sprite.contents().height());
        int px0 = clamp(Math.round(u0 / (float) UV_UNITS * width), 0, width - 1);
        int px1 = clamp(Math.round(u1 / (float) UV_UNITS * width), px0 + 1, width);
        int py0 = clamp(Math.round(v0 / (float) UV_UNITS * height), 0, height - 1);
        int py1 = clamp(Math.round(v1 / (float) UV_UNITS * height), py0 + 1, height);
        long red = 0;
        long green = 0;
        long blue = 0;
        long weight = 0;
        for (int y = py0; y < py1; y++) {
            for (int x = px0; x < px1; x++) {
                int pixel = sprite.getPixelRGBA(0, x, y);
                int alpha = (pixel >>> 24) & 0xFF;
                if (alpha < 8) {
                    continue;
                }
                red += (long) (pixel & 0xFF) * alpha;
                green += (long) ((pixel >>> 8) & 0xFF) * alpha;
                blue += (long) ((pixel >>> 16) & 0xFF) * alpha;
                weight += alpha;
            }
        }
        float[] result;
        if (weight == 0L) {
            result = new float[]{0f, 0f, 0f};
        } else {
            // 平均色太暗时除法会把纹理噪声放大成纯色噪点，给个下限
            result = new float[]{
                    Math.max(0.02f, red / (float) (weight * 255L)),
                    Math.max(0.02f, green / (float) (weight * 255L)),
                    Math.max(0.02f, blue / (float) (weight * 255L))};
        }
        AVG_COLOR.put(key, result);
        return result;
    }

    /**
     * 顶面贴图的面积加权平均色（24 位 RGB），给没有 MapColor 的方块兜底。
     *
     * <p>铁轨、中继器、玻璃板注册时都没写 {@code mapColor(...)}，默认落到
     * {@link net.minecraft.world.level.material.MapColor#NONE}（黑色），照原样画就是整块黑。
     * 没有可画的顶面（十字模型、隐形方块）时返回 -1，交回调用方。
     */
    public static int textureColor(BlockState state) {
        Integer cached = TEXTURE_COLOR.get(state);
        if (cached != null) {
            return cached;
        }
        long red = 0;
        long green = 0;
        long blue = 0;
        long weight = 0;
        for (Part part : of(state).parts()) {
            long area = Math.max(1, part.area());
            float[] avg = avgColor(part.sprite(), part.u0(), part.v0(), part.u1(), part.v1());
            red += Math.round(avg[0] * 255f) * area;
            green += Math.round(avg[1] * 255f) * area;
            blue += Math.round(avg[2] * 255f) * area;
            weight += area;
        }
        int result = weight == 0 ? -1
                : (int) (red / weight) << 16 | (int) (green / weight) << 8 | (int) (blue / weight);
        TEXTURE_COLOR.put(state, result);
        return result;
    }

    /**
     * 这一片顶面自己的平均色（24 位 RGB）。
     *
     * <p>多部件模型里各片颜色不同：中继器的石板、红石火把、滑块在同一个方块里，
     * 用整块均值会把火把染成石板色。只有没写 mapColor 的方块走这条（见
     * {@code MapColors.textureDriven}）。
     */
    public static int partColor(Part part) {
        float[] avg = avgColor(part.sprite(), part.u0(), part.v0(), part.u1(), part.v1());
        return Math.round(avg[0] * 255f) << 16
                | Math.round(avg[1] * 255f) << 8
                | Math.round(avg[2] * 255f);
    }

    /**
     * 按瓦片里记录的贴图 ID 取图集贴图；取不到返回 {@code null}。
     *
     * <p>跨多个图集找：方块图集之外，床、告示牌、箱子各有自己的图集
     * （{@code textures/atlas/beds.png} 等），实体渲染的方块贴图都在那边。
     *
     * <p>必须比对贴图名：{@code TextureAtlas.getSprite} 找不到时返回 missing 占位图
     * 而不是 {@code null}，直接信返回值会把别的图集里的贴图当成 missing 画。
     */
    @Nullable
    public static TextureAtlasSprite sprite(String spriteId) {
        ResourceLocation id = ResourceLocation.tryParse(spriteId);
        if (id == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        for (ResourceLocation atlas : ENTITY_ATLASES) {
            TextureAtlasSprite sprite = minecraft.getTextureAtlas(atlas).apply(id);
            if (sprite != null && id.equals(sprite.contents().name())) {
                return sprite;
            }
        }
        return null;
    }

    /**
     * {@link Material#sprite()} 的同款取图，但只认真正命中名字的贴图：
     * 图集还没加载完时它会返回 missing 占位图，拿占位图取色会得到黑色块。
     */
    @Nullable
    public static TextureAtlasSprite materialSprite(Material material) {
        TextureAtlasSprite sprite = material.sprite();
        if (sprite == null || !material.texture().equals(sprite.contents().name())) {
            return null;
        }
        return sprite;
    }

    /** 图集贴图的像素名（瓦片里存的就是它）；资源重载后仍然有效。 */
    public static String idOf(TextureAtlasSprite sprite) {
        return sprite.contents().name().toString();
    }

    /** 图集宽度（像素）。GUI blit 需要它把 UV 换算回像素。 */
    public static int atlasWidth(TextureAtlasSprite sprite) {
        float u1 = sprite.getU1();
        if (u1 <= 0f) {
            return 1;
        }
        return Math.max(1,
                Math.round((sprite.getX() + sprite.contents().width()) / u1));
    }

    /** 图集高度（像素）。 */
    public static int atlasHeight(TextureAtlasSprite sprite) {
        float v1 = sprite.getV1();
        if (v1 <= 0f) {
            return 1;
        }
        return Math.max(1,
                Math.round((sprite.getY() + sprite.contents().height()) / v1));
    }

    /**
     * 不在图集里的贴图（生物头、玩家皮肤）某个像素矩形的平均色（24 位 RGB）。
     *
     * <p>这些贴图只作为实体渲染的原始 PNG 存在，没有进 TextureAtlas，只能从
     * {@link ResourceManager} 读一次再按像素取平均。资源包换了贴图由
     * {@link #invalidate()} 一起清掉。返回 {@code null} 表示贴图缺失或读失败。
     */
    @Nullable
    public static Integer rawTextureColor(ResourceLocation texture, int x0, int y0, int x1, int y1) {
        String key = texture + "@" + x0 + "," + y0 + "," + x1 + "," + y1;
        if (RAW_TEXTURE_COLOR.containsKey(key)) {
            return RAW_TEXTURE_COLOR.get(key);
        }
        Integer result = null;
        try {
            java.util.Optional<Resource> resource =
                    Minecraft.getInstance().getResourceManager().getResource(texture);
            if (resource.isPresent()) {
                try (InputStream in = resource.get().open();
                     NativeImage image = NativeImage.read(in)) {
                    result = averageRegion(image, x0, y0, x1, y1);
                }
            }
        } catch (IOException | RuntimeException ex) {
            result = null;
        }
        RAW_TEXTURE_COLOR.put(key, result);
        return result;
    }

    /** PNG 像素矩形的 alpha 加权平均色（24 位 RGB）；整片透明返回 {@code null}。 */
    @Nullable
    private static Integer averageRegion(NativeImage image, int x0, int y0, int x1, int y1) {
        int width = image.getWidth();
        int height = image.getHeight();
        int px0 = clamp(x0, 0, width - 1);
        int px1 = clamp(x1, px0 + 1, width);
        int py0 = clamp(y0, 0, height - 1);
        int py1 = clamp(y1, py0 + 1, height);
        long red = 0;
        long green = 0;
        long blue = 0;
        long weight = 0;
        for (int y = py0; y < py1; y++) {
            for (int x = px0; x < px1; x++) {
                int pixel = image.getPixelRGBA(x, y);
                int alpha = (pixel >>> 24) & 0xFF;
                if (alpha < 8) {
                    continue;
                }
                red += (long) (pixel & 0xFF) * alpha;
                green += (long) ((pixel >>> 8) & 0xFF) * alpha;
                blue += (long) ((pixel >>> 16) & 0xFF) * alpha;
                weight += alpha;
            }
        }
        if (weight == 0L) {
            return null;
        }
        return Math.round(red / (float) weight) << 16
                | Math.round(green / (float) weight) << 8
                | Math.round(blue / (float) weight);
    }

    public static void invalidate() {
        CACHE.clear();
        TEXTURE_COLOR.clear();
        AVG_COLOR.clear();
        RAW_TEXTURE_COLOR.clear();
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
