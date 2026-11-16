package example.contentpack.mapv2;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存瓦片仓库：维度 → 层 → 瓦片。
 *
 * <p>层是 {@link MapLayer} 的命名（{@code top} 或 {@code y<absoluteY>}）。
 * 采集和渲染都在客户端主线程，因此 {@link HashMap} 够用。
 */
public final class MapStore {

    private static final Map<ResourceLocation, Map<String, Map<Long, MapTile>>> STORE = new HashMap<>();

    private MapStore() {
    }

    public static MapTile get(ResourceLocation dimension, String layer, int tileX, int tileZ) {
        Map<String, Map<Long, MapTile>> layers = STORE.get(dimension);
        if (layers == null) {
            return null;
        }
        Map<Long, MapTile> tiles = layers.get(layer);
        return tiles == null ? null : tiles.get(key(tileX, tileZ));
    }

    public static MapTile getOrCreate(ResourceLocation dimension, String layer,
                                      int tileX, int tileZ) {
        return STORE
                .computeIfAbsent(dimension, ignored -> new HashMap<>())
                .computeIfAbsent(layer, ignored -> new HashMap<>())
                .computeIfAbsent(key(tileX, tileZ), ignored -> new MapTile());
    }

    /** 从磁盘恢复；已被采集创建的瓦片不覆盖（磁盘数据更旧）。 */
    public static void putIfAbsent(ResourceLocation dimension, String layer,
                                   int tileX, int tileZ, MapTile tile) {
        STORE
                .computeIfAbsent(dimension, ignored -> new HashMap<>())
                .computeIfAbsent(layer, ignored -> new HashMap<>())
                .putIfAbsent(key(tileX, tileZ), tile);
    }

    /** 当前维度内存里已有数据的层（方块变化时用来决定要重采哪些层）。 */
    public static Set<String> layers(ResourceLocation dimension) {
        Map<String, Map<Long, MapTile>> layers = STORE.get(dimension);
        if (layers == null || layers.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new HashSet<>(layers.keySet()));
    }

    /** 取某个方块位置的基色；{@code 0} 表示未采集。 */
    public static int sampleColor(ResourceLocation dimension, String layer,
                                  int blockX, int blockZ) {
        MapTile tile = get(dimension, layer, MapTile.tileCoord(blockX), MapTile.tileCoord(blockZ));
        if (tile == null) {
            return 0;
        }
        int localX = MapTile.localCoord(blockX);
        int localZ = MapTile.localCoord(blockZ);
        return tile.isKnown(localX, localZ) ? tile.colorAt(localX, localZ) : 0;
    }

    /** 取某个方块位置的地表高度；未采集返回 {@link Integer#MIN_VALUE}。 */
    public static int sampleHeight(ResourceLocation dimension, String layer,
                                   int blockX, int blockZ) {
        MapTile tile = get(dimension, layer, MapTile.tileCoord(blockX), MapTile.tileCoord(blockZ));
        if (tile == null) {
            return Integer.MIN_VALUE;
        }
        int localX = MapTile.localCoord(blockX);
        int localZ = MapTile.localCoord(blockZ);
        return tile.isKnown(localX, localZ) ? tile.heightAt(localX, localZ) : Integer.MIN_VALUE;
    }

    /** 该像素采集时记录的方块 ID；未采集或旧存档（v1）返回 null。 */
    public static String sampleBlock(ResourceLocation dimension, String layer,
                                     int blockX, int blockZ) {
        MapTile tile = get(dimension, layer, MapTile.tileCoord(blockX), MapTile.tileCoord(blockZ));
        if (tile == null) {
            return null;
        }
        int localX = MapTile.localCoord(blockX);
        int localZ = MapTile.localCoord(blockZ);
        return tile.isKnown(localX, localZ) ? tile.blockAt(localX, localZ) : null;
    }

    /** 该像素采集时记下的群系 ID；未采集或旧存档（v1/v2）返回 null。 */
    public static String sampleBiome(ResourceLocation dimension, String layer,
                                     int blockX, int blockZ) {
        MapTile tile = get(dimension, layer, MapTile.tileCoord(blockX), MapTile.tileCoord(blockZ));
        if (tile == null) {
            return null;
        }
        int localX = MapTile.localCoord(blockX);
        int localZ = MapTile.localCoord(blockZ);
        return tile.isKnown(localX, localZ) ? tile.biomeAt(localX, localZ) : null;
    }

    /** 正数水深、负数岩浆深度、0 非流体、{@link Integer#MIN_VALUE} 未采集。 */
    public static int sampleFluid(ResourceLocation dimension, String layer,
                                  int blockX, int blockZ) {
        MapTile tile = get(dimension, layer, MapTile.tileCoord(blockX), MapTile.tileCoord(blockZ));
        if (tile == null) {
            return Integer.MIN_VALUE;
        }
        int localX = MapTile.localCoord(blockX);
        int localZ = MapTile.localCoord(blockZ);
        return tile.isKnown(localX, localZ) ? tile.fluidAt(localX, localZ) : Integer.MIN_VALUE;
    }

    /**
     * 叠层采样：按 {@code sampleOrder}（从高到低）找第一个已采集像素。
     *
     * <p>切片留空像素，空处要露出下层；所以这里的层列表必须是
     * {@link MapLayerStack#sampleOrder} 的顺序，而不是单个层。
     */
    public static StackSample sampleStack(ResourceLocation dimension, List<String> sampleOrder,
                                          int blockX, int blockZ) {
        for (String layer : sampleOrder) {
            MapTile tile = get(dimension, layer, MapTile.tileCoord(blockX), MapTile.tileCoord(blockZ));
            if (tile == null) {
                continue;
            }
            int localX = MapTile.localCoord(blockX);
            int localZ = MapTile.localCoord(blockZ);
            if (!tile.isKnown(localX, localZ)) {
                continue;
            }
            return new StackSample(tile.colorAt(localX, localZ), tile.heightAt(localX, localZ),
                    tile.fluidAt(localX, localZ), tile.blockAt(localX, localZ),
                    tile.stackAt(localX, localZ), tile.biomeAt(localX, localZ));
        }
        return UNKNOWN;
    }

    /**
     * 一个像素在叠层里看到的方块与群系；{@link #color()} 为 0 表示整条栈都没采集。
     *
     * <p>{@link #block()} 是终点方块（最上面那块顶面完整且不透明的方块）；
     * {@link #stack()} 是从上看下去的表面栈「上 → 下」（v5 起，旧存档为空），
     * 最后一层是终点，前面的都是半透明/不完整的叠层。
     * {@link #biome()} 是采集时记下的群系 ID（v3 起），区块卸载后仍可用；
     * 旧存档或没记到时为 {@code null}。
     */
    public record StackSample(int color, int height, int fluid, String block,
                              List<MapStackLayer> stack, String biome) {

        public boolean known() {
            return color != 0;
        }

        /**
         * 悬停读数：指针落在格内 {@code localX/localZ}（0..16）时看到的方块。
         *
         * <p>表面栈是「上 → 下」，从上往下找第一层覆盖该点的层 —— 一格里有半块形状
         * 时（告示牌、生物头、火把、箱子边缘），指在形状上就报形状本身，指在露出的
         * 地方就报下一层。没有层覆盖（空栈 / 残缺旧数据）回落栈顶，信息条不空。
         */
        public String blockAt(double localX, double localZ) {
            for (MapStackLayer layer : stack) {
                if (layer.covers(localX, localZ)) {
                    return layer.blockId();
                }
            }
            return visibleBlock();
        }

        /**
         * 表面栈最上面那一层（从上看最先看到的东西），{@link #block()} 则是
         * 「顶面占满整格且不透明」的终点方块。旧瓦片（v5 前没有表面栈）回落终点。
         */
        public String visibleBlock() {
            return stack.isEmpty() ? block : stack.get(0).blockId();
        }
    }

    private static final StackSample UNKNOWN =
            new StackSample(0, Integer.MIN_VALUE, Integer.MIN_VALUE, null, List.of(), null);

    /**
     * 视口内、内存中已存在的瓦片。
     *
     * <p>按已探索瓦片迭代，而不是遍历整个矩形范围——缩到很远时矩形可能有几万个格子，
     * 但真正有数据的通常只有几十到几百个。
     */
    public static List<TileAt> visibleTiles(ResourceLocation dimension, String layer,
                                            int minTileX, int maxTileX,
                                            int minTileZ, int maxTileZ) {
        Map<String, Map<Long, MapTile>> layers = STORE.get(dimension);
        if (layers == null) {
            return List.of();
        }
        Map<Long, MapTile> tiles = layers.get(layer);
        if (tiles == null || tiles.isEmpty()) {
            return List.of();
        }
        List<TileAt> result = new ArrayList<>();
        for (Map.Entry<Long, MapTile> entry : tiles.entrySet()) {
            long key = entry.getKey();
            int tileX = (int) (key >> 32);
            int tileZ = (int) key;
            if (tileX >= minTileX && tileX <= maxTileX
                    && tileZ >= minTileZ && tileZ <= maxTileZ) {
                result.add(new TileAt(tileX, tileZ, entry.getValue()));
            }
        }
        return result;
    }

    public static void clearAll() {
        STORE.clear();
    }

    public record TileAt(int x, int z, MapTile tile) {
    }

    private static long key(int tileX, int tileZ) {
        return ((long) tileX << 32) ^ (tileZ & 0xFFFFFFFFL);
    }
}
