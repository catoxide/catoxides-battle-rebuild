package example.contentpack.biomepreview.preview;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 地表群系采样器：把 depth 参数固定成 0.0，其余 5 个气候参数照常取噪声。
 * <p>原版每个群系参数点都有 depth 槽：地表群系是 {@code point(0.0)}，洞穴群系是
 * {@code span(0.2, 0.9)}，深暗是 {@code point(1.1)}（OverworldBiomeBuilder
 * addSurfaceBiome / addUndergroundBiome / addBottomBiome）。所以把 depth 换成常量
 * 不影响那 5 个参数、也不需要任何 y 扫描，就能拿到「该列地表网格的群系」。
 * <p>末地用 {@link TheEndBiomeSource}：它只读 {@code sampler.erosion()}（按侵蚀分高地/外岛），
 * 温度/humidity/depth 都不参与，所以直接用原版 sampler，不做 depth=0 改写。
 * <p>另外两种采样都走同一个 {@link RandomState}：
 * <ul>
 *   <li>{@link #surfaceAt} —— depth=0 的预测值（预览用）</li>
 *   <li>{@link #actualBiomeAt} —— 原版 sampler（真实 depth），与 chunk 里
 *       {@code fillBiomesFromNoise} 写入的值同源，只差 Cache2D/FlatCache 包装（值相同）</li>
 * </ul>
 * 线程安全：RandomState 的 router 不可变、NormalNoise 无共享可变状态、Climate.RTree
 * 的 lastResult 是 ThreadLocal，因此采样可以跨线程并行。
 */
public final class SurfaceBiomeSampler {

    /** 预览的实际最小分辨率：原版 Climate.Sampler 用 QuartPos 把坐标量化到 4 格。 */
    public static final int MIN_STEP_BLOCKS = 4;

    private final BiomeSource biomeSource;
    private final Climate.Sampler surfaceSampler;
    private final Climate.Sampler actualSampler;

    public SurfaceBiomeSampler(BiomeSource biomeSource, RandomState randomState) {
        this.biomeSource = biomeSource;
        if (biomeSource instanceof MultiNoiseBiomeSource) {
            Climate.Sampler base = randomState.sampler();
            this.actualSampler = base;
            this.surfaceSampler = new Climate.Sampler(
                    base.temperature(),
                    base.humidity(),
                    base.continentalness(),
                    base.erosion(),
                    DensityFunctions.constant(0.0),
                    base.weirdness(),
                    base.spawnTarget()
            );
        } else if (biomeSource instanceof TheEndBiomeSource) {
            Climate.Sampler base = randomState.sampler();
            this.actualSampler = base;
            this.surfaceSampler = base;
        } else if (biomeSource instanceof FixedBiomeSource) {
            // 单一群系世界（超平坦等）：没有气候采样，getNoiseBiome 会忽略 sampler
            this.actualSampler = null;
            this.surfaceSampler = null;
        } else {
            throw new IllegalArgumentException(
                    "Unsupported biome source: " + biomeSource.getClass().getName());
        }
    }

    /** 支持多噪声（常规世界）、末地源和单群系（超平坦/自定义单一群系）。 */
    public static boolean supports(BiomeSource biomeSource) {
        return biomeSource instanceof MultiNoiseBiomeSource
                || biomeSource instanceof TheEndBiomeSource
                || biomeSource instanceof FixedBiomeSource;
    }

    public BiomeSource biomeSource() {
        return biomeSource;
    }

    /** 预测：该列地表网格的群系（y 不参与计算，传 0 即可）。 */
    public Holder<Biome> surfaceAt(int blockX, int blockZ) {
        return biomeSource.getNoiseBiome(
                QuartPos.fromBlock(blockX), 0, QuartPos.fromBlock(blockZ), surfaceSampler);
    }

    /** 该采样器是否需要 RandomState（单群系世界不需要）。 */
    public boolean needsRandomState() {
        return surfaceSampler != null;
    }

    public String surfaceBiomeId(int blockX, int blockZ) {
        return idOf(surfaceAt(blockX, blockZ));
    }

    /** 原版采样：真实 depth 在该坐标处的群系，即 chunk 实际记录的值。 */
    public String actualBiomeAt(int blockX, int blockY, int blockZ) {
        return idOf(biomeSource.getNoiseBiome(
                QuartPos.fromBlock(blockX), QuartPos.fromBlock(blockY), QuartPos.fromBlock(blockZ), actualSampler));
    }

    public static String idOf(Holder<Biome> biome) {
        return biome.unwrapKey().map(ResourceKey::location).map(Object::toString).orElse("minecraft:unknown");
    }
}
