package example.contentpack.mapv2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一块 512×512 方块的区域瓦片（top 层）。
 *
 * <p>每像素存两个通道：
 * <ul>
 *   <li>{@code argb} —— 方块基色（已含群系 tint，<b>不含</b>高度明暗），渲染时才乘阴影，
 *       所以以后改阴影/主题不需要重新采集。</li>
 *   <li>{@code height} —— 该列表面方块的世界 y，用于明暗和后续等高线。</li>
 * </ul>
 * {@code known=false} 表示这一列还没被采集过（渲染成透明），不是「空气」。
 *
 * <p>区域取 512 格（= 32×32 区块），一个区域文件、一张区域纹理，
 * 比 128 格少 16 倍纹理与绘制调用、少 16 倍文件。
 *
 * <p>只在客户端主线程读写（采集 tick + 渲染），不加锁。
 */
public final class MapTile {

    public static final int SIZE = 512;
    public static final int SHIFT = 9;
    public static final int MASK = SIZE - 1;

    private final int[] argb;
    private final short[] height;
    /** 流体：正数 = 水深，负数 = 岩浆深度，0 = 非流体。 */
    private final byte[] fluid;
    private final boolean[] known;
    /** 瓦片级方块调色板 + 每像素下标；未采集像素下标无意义。 */
    private final List<String> blockPalette = new ArrayList<>();
    private final Map<String, Integer> blockLookup = new HashMap<>();
    private final short[] blockIndex;
    /** 每次整块重采后 +1；纹理缓存用它判断要不要重建。 */
    private int revision;
    /** 已采集像素数，O(1) 读取（视口覆盖率诊断用）。 */
    private int knownCount;

    public MapTile() {
        this(new int[SIZE * SIZE], new short[SIZE * SIZE], new byte[SIZE * SIZE],
                new boolean[SIZE * SIZE], new short[SIZE * SIZE], List.of());
    }

    private MapTile(int[] argb, short[] height, byte[] fluid, boolean[] known,
                    short[] blockIndex, List<String> blockPalette) {
        this.argb = argb;
        this.height = height;
        this.fluid = fluid;
        this.known = known;
        this.blockIndex = blockIndex;
        this.blockPalette.addAll(blockPalette);
        for (int i = 0; i < this.blockPalette.size(); i++) {
            this.blockLookup.put(this.blockPalette.get(i), i);
        }
        for (boolean flag : known) {
            if (flag) {
                knownCount++;
            }
        }
    }

    /** 从磁盘快照重建；长度不符直接拒绝（损坏文件）。 */
    public static MapTile fromData(int[] argb, short[] height, byte[] fluid, boolean[] known,
                                   short[] blockIndex, List<String> blockPalette) {
        if (argb.length != SIZE * SIZE || height.length != SIZE * SIZE
                || fluid.length != SIZE * SIZE || known.length != SIZE * SIZE
                || blockIndex.length != SIZE * SIZE) {
            throw new IllegalArgumentException("bad tile array length");
        }
        return new MapTile(argb, height, fluid, known, blockIndex, blockPalette);
    }

    public int revision() {
        return revision;
    }

    public void bumpRevision() {
        revision++;
    }

    public int[] copyArgb() {
        return argb.clone();
    }

    public short[] copyHeight() {
        return height.clone();
    }

    public byte[] copyFluid() {
        return fluid.clone();
    }

    public boolean[] copyKnown() {
        return known.clone();
    }

    /** 方块坐标 → 瓦片序号（向下取整，负数也正确）。 */
    public static int tileCoord(int block) {
        return block >> SHIFT;
    }

    /** 方块坐标 → 瓦片内局部坐标。 */
    public static int localCoord(int block) {
        return block & MASK;
    }

    /** @return 这次写入是否真的改变了像素（重新进世界时用它跳过无变化的落盘与重绘） */
    public boolean set(int localX, int localZ, int color, int y, int fluidDepth, String blockId) {
        int index = localZ * SIZE + localX;
        short packedY = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, y));
        byte packedFluid = (byte) Math.max(-127, Math.min(127, fluidDepth));
        short packedBlock = (short) paletteIndex(blockId);
        boolean changed = !known[index] || argb[index] != color
                || height[index] != packedY || fluid[index] != packedFluid
                || blockIndex[index] != packedBlock;
        if (!known[index]) {
            knownCount++;
        }
        argb[index] = color;
        height[index] = packedY;
        fluid[index] = packedFluid;
        blockIndex[index] = packedBlock;
        known[index] = true;
        return changed;
    }

    /**
     * 用 {@code other} 的已知像素补本瓦片的未知像素。
     *
     * <p>用途：重新进世界时采集会先建一张空瓦片，磁盘上那块区域的旧数据必须补进来，
     * 否则空像素会随落盘把旧数据抹掉。反过来不行 —— 内存里的像素总是更新。
     *
     * @return 补进来的像素数
     */
    public int fillUnknownFrom(MapTile other) {
        int filled = 0;
        for (int localZ = 0; localZ < SIZE; localZ++) {
            for (int localX = 0; localX < SIZE; localX++) {
                int index = localZ * SIZE + localX;
                if (known[index] || !other.known[index]) {
                    continue;
                }
                int palette = other.blockIndex[index];
                String blockId = palette >= 0 && palette < other.blockPalette.size()
                        ? other.blockPalette.get(palette) : null;
                set(localX, localZ, other.argb[index], other.height[index],
                        other.fluid[index], blockId);
                filled++;
            }
        }
        return filled;
    }

    private int paletteIndex(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return 0;
        }
        Integer existing = blockLookup.get(blockId);
        if (existing != null) {
            return existing;
        }
        int index = blockPalette.size();
        blockPalette.add(blockId);
        blockLookup.put(blockId, index);
        return index;
    }

    /** 该像素记录时的方块 ID；未采集或旧存档（v1）返回 null。 */
    public String blockAt(int localX, int localZ) {
        int index = localZ * SIZE + localX;
        if (!known[index]) {
            return null;
        }
        int paletteIndex = blockIndex[index];
        return paletteIndex >= 0 && paletteIndex < blockPalette.size()
                ? blockPalette.get(paletteIndex) : null;
    }

    public List<String> copyBlockPalette() {
        return new ArrayList<>(blockPalette);
    }

    public short[] copyBlockIndex() {
        return blockIndex.clone();
    }

    public boolean isKnown(int localX, int localZ) {
        return known[localZ * SIZE + localX];
    }

    public int colorAt(int localX, int localZ) {
        return argb[localZ * SIZE + localX];
    }

    public int heightAt(int localX, int localZ) {
        return height[localZ * SIZE + localX];
    }

    /** 正数 = 水深，负数 = 岩浆深度，0 = 非流体。 */
    public int fluidAt(int localX, int localZ) {
        return fluid[localZ * SIZE + localX];
    }

    /** 已采集的像素数。 */
    public int knownCount() {
        return knownCount;
    }
}
