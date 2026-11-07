package example.contentpack.mapv2.reveal;

import example.contentpack.mapv2.structure.StructureScan;

/**
 * 探明范围的纯逻辑自检：网格位序、半径、负数坐标、增量幂等、结构单元计数。
 *
 * <p>不依赖世界/网络；失败抛 {@link AssertionError}，由调用方记 ERROR。
 */
public final class RevealSelfTest {

    private RevealSelfTest() {
    }

    public static void run() {
        masks();
        negatives();
        chunkSet();
        structureCells();
    }

    private static void masks() {
        require(RevealGrid.maskForCell(0, 0, 0, 0, 0) == 0L, "radius 0 must reveal nothing");

        int small = 0;
        for (int chunkX = -1; chunkX <= 1; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                if (covered(0, 0, 1, chunkX, chunkZ)) {
                    small++;
                }
            }
        }
        require(small == 5, "radius 1 covers centre + 4 neighbours: " + small);
        require(covered(0, 0, 1, 0, 0), "centre chunk must be set");
        require(covered(0, 0, 1, 1, 0), "east chunk must be set");
        require(covered(0, 0, 1, -1, 0), "west chunk must be set");
        require(covered(0, 0, 1, 0, 1), "south chunk must be set");
        require(covered(0, 0, 1, 0, -1), "north chunk must be set");
        require(!covered(0, 0, 1, 1, 1), "diagonal chunk is outside radius 1");

        long full = RevealGrid.maskForCell(0, 0, 0, 0, 64);
        require(full == -1L, "a cell centred on the player is fully inside radius 64");
        long far = RevealGrid.maskForCell(8, 8, 0, 0, 64);
        require(far == 0L, "a cell 64 chunks away reveals nothing at radius 64");
        long edge = RevealGrid.maskForCell(8, 0, 0, 0, 64);
        require(Long.bitCount(edge) > 0 && edge != -1L, "edge cell is partially revealed");
    }

    /** 跨格的圆要按「区块所在格」取掩码，不能拿一格去代表整个半径。 */
    private static boolean covered(int centerChunkX, int centerChunkZ, int radius,
                                   int chunkX, int chunkZ) {
        long mask = RevealGrid.maskForCell(RevealGrid.cellOfChunk(chunkX),
                RevealGrid.cellOfChunk(chunkZ), centerChunkX, centerChunkZ, radius);
        return (mask & (1L << RevealGrid.bit(chunkX, chunkZ))) != 0L;
    }

    private static void negatives() {
        require(covered(-8, -8, 1, -8, -8), "centre chunk of a negative cell must be set");
        require(covered(-8, -8, 1, -9, -8), "west neighbour of a negative chunk");
        require(!covered(-8, -8, 1, -9, -9), "diagonal of a negative chunk stays hidden");

        long key = RevealGrid.key(-3, 7);
        require(RevealGrid.keyX(key) == -3 && RevealGrid.keyZ(key) == 7, "key roundtrip negative x");
        long key2 = RevealGrid.key(12, -40);
        require(RevealGrid.keyX(key2) == 12 && RevealGrid.keyZ(key2) == -40, "key roundtrip negative z");
    }

    private static void chunkSet() {
        RevealChunkSet set = new RevealChunkSet();
        long chunkMask = 1L << RevealGrid.bit(3, 5);
        long added = set.add(0, 0, chunkMask);
        require(added == chunkMask, "first add reports the new bit");
        require(set.isRevealed(3, 5), "chunk (3,5) must be revealed");
        require(!set.isRevealed(4, 5), "neighbouring chunk stays hidden");
        require(set.add(0, 0, chunkMask) == 0L, "re-adding the same bit is a no-op");
        require(set.size() == 1, "one cell expected: " + set.size());

        RevealChunkSet negative = new RevealChunkSet();
        negative.add(-1, -1, 1L);
        require(negative.isRevealed(-8, -8), "negative cell bit 0 is chunk (-8,-8)");
        require(!negative.isRevealed(-9, -9), "chunk outside the revealed cell stays hidden");
    }

    private static void structureCells() {
        RevealChunkSet set = new RevealChunkSet();
        set.add(0, 0, 1L << RevealGrid.bit(0, 0));
        set.add(1, 1, 1L << RevealGrid.bit(8, 8));
        require(set.hasAnyInStructureCell(0, 0), "both cells belong to structure cell 0/0");
        require(!set.hasAnyInStructureCell(-1, 0), "empty structure cell must stay empty");

        set.add(8, 0, 1L << RevealGrid.bit(64, 0));
        require(set.hasAnyInStructureCell(1, 0), "chunk 64 belongs to structure cell 1/0");

        for (int chunk = -130; chunk <= 130; chunk += 7) {
            require(RevealGrid.structureCell(RevealGrid.cellOfChunk(chunk))
                            == StructureScan.cellIndex(chunk),
                    "reveal grid must align with structure cells at chunk " + chunk);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}