package example.contentpack.mapv2.reveal;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * 一个维度的探明区块集合。
 *
 * <p>底层是「8×8 区块格 → 64 位掩码」，增量只发新置的位；重复应用同一增量是幂等的。
 * 同时按结构查询单元（64 区块）累计已探明区块数，客户端用来跳过完全没探明的单元。
 *
 * <p>坐标全部按 {@link RevealGrid} 的约定，负数同样成立。
 */
public final class RevealChunkSet {

    private final Long2LongOpenHashMap cells = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap structureCells = new Long2IntOpenHashMap();

    /** @return 本次新置的位；0 表示没有变化 */
    public long add(int cellX, int cellZ, long mask) {
        return add(RevealGrid.key(cellX, cellZ), mask);
    }

    /** @return 本次新置的位；0 表示没有变化 */
    public long add(long cellKey, long mask) {
        if (mask == 0L) {
            return 0L;
        }
        long old = cells.get(cellKey);
        long added = mask & ~old;
        if (added == 0L) {
            return 0L;
        }
        cells.put(cellKey, old | mask);
        long structureKey = RevealGrid.key(
                RevealGrid.structureCell(RevealGrid.keyX(cellKey)),
                RevealGrid.structureCell(RevealGrid.keyZ(cellKey)));
        structureCells.addTo(structureKey, Long.bitCount(added));
        return added;
    }

    public boolean isRevealed(int chunkX, int chunkZ) {
        long mask = cells.get(RevealGrid.key(RevealGrid.cellOfChunk(chunkX),
                RevealGrid.cellOfChunk(chunkZ)));
        return (mask & (1L << RevealGrid.bit(chunkX, chunkZ))) != 0L;
    }

    /** 该结构单元里至少有一个区块被探明。 */
    public boolean hasAnyInStructureCell(int structureCellX, int structureCellZ) {
        return structureCells.get(RevealGrid.key(structureCellX, structureCellZ)) > 0;
    }

    public int size() {
        return cells.size();
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /** 只读视图，供服务端快照/持久化遍历。调用方不得修改。 */
    public Long2LongMap cells() {
        return cells;
    }
}