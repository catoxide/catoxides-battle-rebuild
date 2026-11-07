package example.contentpack.mapv2.structure;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/**
 * 结构候选点的纯算术：区域归属、查询单元格、矩形判定、群系采样高度、变体顺序。
 *
 * <p>不读世界、不碰注册表，自检可以直接跑。候选点本身仍由原版
 * {@code RandomSpreadStructurePlacement.getPotentialStructureChunk} 计算——这里只回答
 * 「矩形覆盖了哪些区域 / 该格属于哪个缓存单元 / 多条目结构集先试哪个变体」。
 */
public final class StructureScan {

    /** 一次查询覆盖的区块边长；客户端与服务端共用同一个常量，协议里不再传。 */
    public static final int CELL_CHUNKS = 64;

    private StructureScan() {
    }

    /**
     * 群系门的采样高度：底部附近 / 海平面 / 海平面以上一档。
     *
     * <p>原版 {@code Structure.isValidBiome} 采样的是结构锚点高度，而锚点由各结构自己的
     * {@code findGenerationPoint} 决定（地表、海床、深层都可能），离线复算拿不到。
     * 这里用三个高度覆盖「地表 / 水下 / 地下」三类结构，任一命中即放行——
     * 宁可多报也不误杀真结构。
     */
    public static int[] sampleHeights(int minY, int maxY, int seaLevel) {
        int last = Math.max(minY, maxY - 1);
        int[] raw = {minY + 8, seaLevel, seaLevel + 32};
        int[] out = new int[raw.length];
        int count = 0;
        for (int value : raw) {
            int clamped = Math.max(minY, Math.min(last, value));
            boolean duplicate = false;
            for (int i = 0; i < count; i++) {
                if (out[i] == clamped) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                out[count++] = clamped;
            }
        }
        return java.util.Arrays.copyOf(out, count);
    }

    /** 区块坐标落在哪个 spread 区域（可能为负，必须用 floorDiv 而不是整数除法）。 */
    public static int regionIndex(int chunk, int spacing) {
        return Math.floorDiv(chunk, spacing);
    }

    /** 区块坐标落在哪个查询单元。 */
    public static int cellIndex(int chunk) {
        return Math.floorDiv(chunk, CELL_CHUNKS);
    }

    /** 单元格的最小区块坐标。 */
    public static int cellOrigin(int cell) {
        return cell * CELL_CHUNKS;
    }

    public static boolean inRect(int chunkX, int chunkZ,
                                 int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        return chunkX >= minChunkX && chunkX <= maxChunkX
                && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    /**
     * 多条目结构集的变体尝试顺序，与 {@code ChunkGenerator.createStructures} 同步：
     * {@code WorldgenRandom.setLargeFeatureSeed(seed, chunkX, chunkZ)} 之后按权重挑，
     * 挑中的变体生不出结构（群系不符 / 地形高度不够）时原版才试下一个。
     *
     * <p>所以首项就是「这个落点最可能长出来的变体」。下界复合结构集同时含要塞与堡垒遗迹，
     * 单看结构集 id 分不出是哪个，必须按这套顺序取变体。
     *
     * @param weights 与结构集 {@code structures()} 同序的权重
     * @return 条目下标数组（长度与 weights 相同，升序无重复）
     */
    public static int[] variantOrder(long seed, int chunkX, int chunkZ, int[] weights) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, chunkX, chunkZ);
        int[] order = new int[weights.length];
        boolean[] used = new boolean[weights.length];
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        for (int slot = 0; slot < order.length; slot++) {
            int roll = random.nextInt(total);
            int chosen = -1;
            for (int i = 0; i < weights.length; i++) {
                if (used[i]) {
                    continue;
                }
                roll -= weights[i];
                if (roll < 0) {
                    chosen = i;
                    break;
                }
            }
            if (chosen < 0) {
                // 权重非正时原版行为无定义；兜底取第一个未用条目，保证是排列
                for (int i = 0; i < weights.length; i++) {
                    if (!used[i]) {
                        chosen = i;
                        break;
                    }
                }
            }
            order[slot] = chosen;
            used[chosen] = true;
            total -= weights[chosen];
        }
        return order;
    }
}
