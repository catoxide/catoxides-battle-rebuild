package example.contentpack.mapv2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一块 512×512 方块的区域瓦片（top 层）。
 *
 * <p>每像素存五个通道：
 * <ul>
 *   <li>{@code argb} —— 方块基色（已含群系 tint 与叠层混色，<b>不含</b>高度明暗），
 *       渲染时才乘阴影，所以以后改阴影/主题不需要重新采集。</li>
 *   <li>{@code height} —— 该列表面方块的世界 y，用于明暗和后续等高线。</li>
 *   <li>{@code blockIndex} —— 完整表面（最上面那块朝上面不透明的方块）的 ID。</li>
 *   <li>{@code stackIndex} —— 这一列从上看下去的表面栈（下标指向瓦片级栈调色板）；
 *       {@code -1} = 没有叠层。数组惰性分配：整块瓦片都没叠层时不占 512 KiB。</li>
 *   <li>{@code biomeIndex} —— 当时那一列所在的群系 ID（{@code -1} = 旧存档没记）；
 *       区块卸载后靠它保留「确定群系」，不用回落成预测。</li>
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
    /**
     * 表面栈调色板（每项是一列「上 → 下」的层）+ 每像素下标；{@code -1} = 没有栈。
     * 数组惰性分配：大部分瓦片没有叠层，不必每块白占 512 KiB。
     */
    private final List<List<MapStackLayer>> stackPalette = new ArrayList<>();
    private final Map<List<MapStackLayer>, Integer> stackLookup = new HashMap<>();
    private short[] stackIndex;
    /** 群系 ID 调色板 + 每像素下标；{@code -1} = 这列采集时没记群系（旧存档）。 */
    private final List<String> biomePalette = new ArrayList<>();
    private final Map<String, Integer> biomeLookup = new HashMap<>();
    private final short[] biomeIndex;
    /** 每次整块重采后 +1；纹理缓存用它判断要不要重建。 */
    private int revision;
    /** 已采集像素数，O(1) 读取（视口覆盖率诊断用）。 */
    private int knownCount;

    public MapTile() {
        this(new int[SIZE * SIZE], new short[SIZE * SIZE], new byte[SIZE * SIZE],
                new boolean[SIZE * SIZE], new short[SIZE * SIZE], List.of(),
                null, List.of(), unknownBiomeIndex(), List.of());
    }

    private static short[] unknownBiomeIndex() {
        short[] indices = new short[SIZE * SIZE];
        java.util.Arrays.fill(indices, (short) -1);
        return indices;
    }

    private MapTile(int[] argb, short[] height, byte[] fluid, boolean[] known,
                    short[] blockIndex, List<String> blockPalette,
                    short[] stackIndex, List<List<MapStackLayer>> stackPalette,
                    short[] biomeIndex, List<String> biomePalette) {
        this.argb = argb;
        this.height = height;
        this.fluid = fluid;
        this.known = known;
        this.blockIndex = blockIndex;
        this.blockPalette.addAll(blockPalette);
        for (int i = 0; i < this.blockPalette.size(); i++) {
            this.blockLookup.put(this.blockPalette.get(i), i);
        }
        this.stackIndex = stackIndex;
        this.stackPalette.addAll(stackPalette);
        for (int i = 0; i < this.stackPalette.size(); i++) {
            this.stackLookup.put(this.stackPalette.get(i), i);
        }
        this.biomeIndex = biomeIndex;
        this.biomePalette.addAll(biomePalette);
        for (int i = 0; i < this.biomePalette.size(); i++) {
            this.biomeLookup.put(this.biomePalette.get(i), i);
        }
        for (boolean flag : known) {
            if (flag) {
                knownCount++;
            }
        }
    }

    /** 从磁盘快照重建；长度不符直接拒绝（损坏文件）。 */
    public static MapTile fromData(int[] argb, short[] height, byte[] fluid, boolean[] known,
                                   short[] blockIndex, List<String> blockPalette,
                                   short[] stackIndex, List<List<MapStackLayer>> stackPalette,
                                   short[] biomeIndex, List<String> biomePalette) {
        if (argb.length != SIZE * SIZE || height.length != SIZE * SIZE
                || fluid.length != SIZE * SIZE || known.length != SIZE * SIZE
                || blockIndex.length != SIZE * SIZE || biomeIndex.length != SIZE * SIZE
                || (stackIndex != null && stackIndex.length != SIZE * SIZE)) {
            throw new IllegalArgumentException("bad tile array length");
        }
        return new MapTile(argb, height, fluid, known, blockIndex, blockPalette,
                stackIndex, stackPalette, biomeIndex, biomePalette);
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

    /**
     * @param stack 这一列从上看下去的表面栈（「上 → 下」，可含终点层）；null/空 = 没有叠层
     * @param biomeId 这一列采集时所在群系（可为 null：旧存档/取不到）；写进瓦片后
     *                区块卸载了也还能显示确定群系，而不是回落成预测值
     * @return 这次写入是否真的改变了像素（重新进世界时用它跳过无变化的落盘与重绘）
     */
    public boolean set(int localX, int localZ, int color, int y, int fluidDepth, String blockId,
                       List<MapStackLayer> stack, String biomeId) {
        int index = localZ * SIZE + localX;
        short packedY = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, y));
        byte packedFluid = (byte) Math.max(-127, Math.min(127, fluidDepth));
        short packedBlock = (short) paletteIndex(blockId);
        short packedStack = (short) stackPaletteIndex(stack);
        short packedBiome = (short) biomePaletteIndex(biomeId);
        boolean changed = !known[index] || argb[index] != color
                || height[index] != packedY || fluid[index] != packedFluid
                || blockIndex[index] != packedBlock || biomeIndex[index] != packedBiome
                || stackAtRaw(index) != packedStack;
        if (!known[index]) {
            knownCount++;
        }
        argb[index] = color;
        height[index] = packedY;
        fluid[index] = packedFluid;
        blockIndex[index] = packedBlock;
        if (packedStack >= 0) {
            if (stackIndex == null) {
                stackIndex = unknownStackIndex();
            }
            stackIndex[index] = packedStack;
        } else if (stackIndex != null) {
            stackIndex[index] = -1;
        }
        biomeIndex[index] = packedBiome;
        known[index] = true;
        return changed;
    }

    private static short[] unknownStackIndex() {
        short[] indices = new short[SIZE * SIZE];
        java.util.Arrays.fill(indices, (short) -1);
        return indices;
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
                int biomePalette = other.biomeIndex[index];
                String biomeId = biomePalette >= 0 && biomePalette < other.biomePalette.size()
                        ? other.biomePalette.get(biomePalette) : null;
                int stackPalette = other.stackAtRaw(index);
                List<MapStackLayer> stack = stackPalette >= 0
                        && stackPalette < other.stackPalette.size()
                        ? other.stackPalette.get(stackPalette) : List.of();
                set(localX, localZ, other.argb[index], other.height[index],
                        other.fluid[index], blockId, stack, biomeId);
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

    private int stackAtRaw(int index) {
        return stackIndex == null ? -1 : stackIndex[index];
    }

    private int stackPaletteIndex(List<MapStackLayer> stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        List<MapStackLayer> key = List.copyOf(stack);
        Integer existing = stackLookup.get(key);
        if (existing != null) {
            return existing;
        }
        if (stackPalette.size() >= Short.MAX_VALUE) {
            return -1;
        }
        int index = stackPalette.size();
        stackPalette.add(key);
        stackLookup.put(key, index);
        return index;
    }

    /** 该像素从上看下去的表面栈（「上 → 下」）；没有叠层 / 未采集返回空列表。 */
    public List<MapStackLayer> stackAt(int localX, int localZ) {
        int index = localZ * SIZE + localX;
        if (!known[index]) {
            return List.of();
        }
        int paletteIndex = stackAtRaw(index);
        return paletteIndex >= 0 && paletteIndex < stackPalette.size()
                ? stackPalette.get(paletteIndex) : List.of();
    }

    public List<List<MapStackLayer>> copyStackPalette() {
        return new ArrayList<>(stackPalette);
    }

    /** 整块没有任何栈时返回 null（磁盘上不写这一段）。 */
    public short[] copyStackIndex() {
        return stackIndex == null ? null : stackIndex.clone();
    }

    private int biomePaletteIndex(String biomeId) {
        if (biomeId == null || biomeId.isEmpty()) {
            return -1;
        }
        Integer existing = biomeLookup.get(biomeId);
        if (existing != null) {
            return existing;
        }
        if (biomePalette.size() >= Short.MAX_VALUE) {
            return -1; // 512×512 里不可能有这么多群系；满了说明数据坏了，不写比写错好
        }
        int index = biomePalette.size();
        biomePalette.add(biomeId);
        biomeLookup.put(biomeId, index);
        return index;
    }

    /** 采集时记下的群系 ID；未采集或旧存档（v1/v2）返回 null。 */
    public String biomeAt(int localX, int localZ) {
        int index = localZ * SIZE + localX;
        if (!known[index]) {
            return null;
        }
        int paletteIndex = biomeIndex[index];
        return paletteIndex >= 0 && paletteIndex < biomePalette.size()
                ? biomePalette.get(paletteIndex) : null;
    }

    public List<String> copyBiomePalette() {
        return new ArrayList<>(biomePalette);
    }

    public short[] copyBiomeIndex() {
        return biomeIndex.clone();
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
