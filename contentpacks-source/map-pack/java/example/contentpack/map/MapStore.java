package example.contentpack.map;

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
