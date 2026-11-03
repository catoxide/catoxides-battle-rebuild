package example.contentpack.mapv2.structure;

/**
 * 结构图层的一条候选点，坐标以<b>区块</b>为单位（D95）。
 *
 * <p>{@code setId} 是结构集的注册表 id（如 {@code minecraft:villages}），不是单个结构变体：
 * 多条目结构集（村庄有 5 个变体）在候选级还没走到群系/权重判定，按集合显示才不会给出假精度。
 */
public record StructureEntry(String setId, int chunkX, int chunkZ, byte tier) {

    /** 纯落点计算，未做群系 / 结构和其它结构的排除检查。 */
    public static final byte TIER_CANDIDATE = 1;
    /**
     * 区块已加载，原版的 STRUCTURE_STARTS 已经算过，该区块里确实有这个结构集的起点。
     * 与候选级同一个坐标语义（起点所在区块），只是从「推测」升成「已确认」。
     */
    public static final byte TIER_CONFIRMED = 2;

    public boolean confirmed() {
        return tier == TIER_CONFIRMED;
    }

    public int blockX() {
        return chunkX * 16 + 8;
    }

    public int blockZ() {
        return chunkZ * 16 + 8;
    }
}
