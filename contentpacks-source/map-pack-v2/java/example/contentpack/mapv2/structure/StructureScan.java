package example.contentpack.mapv2.structure;

/**
 * 结构候选点的纯算术：区域归属、查询单元格、矩形判定、群系采样高度。
 *
 * <p>不读世界、不碰注册表，自检可以直接跑。候选点本身仍由原版
 * {@code RandomSpreadStructurePlacement.getPotentialStructureChunk} 计算——这里只回答
 * 「矩形覆盖了哪些区域 / 该格属于哪个缓存单元」。
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
}
