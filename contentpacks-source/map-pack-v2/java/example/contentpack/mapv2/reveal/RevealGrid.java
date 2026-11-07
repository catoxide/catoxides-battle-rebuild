package example.contentpack.mapv2.reveal;

/**
 * 「探明范围」的网格：8×8 区块（128 格）为一格，每格用一个 64 位掩码表示。
 *
 * <p>掩码按 {@code (dz << 3) | dx} 置位，与区块在格内的局部坐标一一对应。
 * 服务端按玩家位置每秒累加这个集合，客户端据此决定要不要画群系/结构预测。
 *
 * <p>只做整数运算，不碰世界与网络，自检直接跑。
 */
public final class RevealGrid {

    /** 一格覆盖的区块边长。 */
    public static final int CELL_CHUNKS = 8;
    public static final int CELL_SHIFT = 3;
    /** 一格正好 64 个区块，对应一个 long。 */
    public static final int CELL_BITS = CELL_CHUNKS * CELL_CHUNKS;

    /** 结构查询单元（{@code StructureScan.CELL_CHUNKS}）相当于几格。 */
    public static final int STRUCTURE_SHIFT = 3;
    public static final int STRUCTURE_CELLS = 1 << STRUCTURE_SHIFT;

    private RevealGrid() {
    }

    /** 格坐标打包成可比较的 long（高 32 位 x，低 32 位 z）。 */
    public static long key(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    public static int cellOfChunk(int chunk) {
        return chunk >> CELL_SHIFT;
    }

    /** 区块在格内的位序号。 */
    public static int bit(int chunkX, int chunkZ) {
        return ((chunkZ & (CELL_CHUNKS - 1)) << CELL_SHIFT) | (chunkX & (CELL_CHUNKS - 1));
    }

    public static boolean inside(int chunkX, int chunkZ,
                                 int centerChunkX, int centerChunkZ, int radiusChunks) {
        int dx = chunkX - centerChunkX;
        int dz = chunkZ - centerChunkZ;
        return dx * dx + dz * dz <= radiusChunks * radiusChunks;
    }

    /**
     * 一格内落在半径圆里的区块掩码；整格都在范围内时返回 {@code -1L}。
     *
     * @param radiusChunks 0 返回 0（关闭）
     */
    public static long maskForCell(int cellX, int cellZ,
                                   int centerChunkX, int centerChunkZ, int radiusChunks) {
        if (radiusChunks <= 0) {
            return 0L;
        }
        long mask = 0L;
        int baseX = cellX << CELL_SHIFT;
        int baseZ = cellZ << CELL_SHIFT;
        for (int dz = 0; dz < CELL_CHUNKS; dz++) {
            int chunkZ = baseZ + dz;
            for (int dx = 0; dx < CELL_CHUNKS; dx++) {
                int chunkX = baseX + dx;
                if (inside(chunkX, chunkZ, centerChunkX, centerChunkZ, radiusChunks)) {
                    mask |= 1L << bit(chunkX, chunkZ);
                }
            }
        }
        return mask;
    }

    /** 8 区块格 → 结构查询单元（64 区块）。 */
    public static int structureCell(int revealCell) {
        return revealCell >> STRUCTURE_SHIFT;
    }
}