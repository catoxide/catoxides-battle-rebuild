package example.contentpack.mapv2.client;

import com.mojang.blaze3d.platform.NativeImage;
import example.contentpack.mapv2.MapShade;
import example.contentpack.mapv2.MapTile;
import example.contentpack.mapv2.MapStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 瓦片纹理 + 多级 LOD：像图片编辑器一样缩放。
 *
 * <p>每块 512×512 瓦片按需生成 512/256/128/64/32/16 六级纹理——只有当前缩放档真正用到的那级才建，
 * 缩小到某个档位时直接用对应级别，而不是把 512 的纹理硬缩到 4 像素（GPU 双线性只会采 4 个点）。
 *
 * <p><b>缓存跨界面存活</b>：关掉地图不会丢掉纹理，再打开是直接画（这是以前「重开地图重新算一遍」
 * 的根源）；换世界时才由 {@link #clearAll()} 清空。
 *
 * <p><b>预测群系是独立的一层，且不依赖瓦片是否存在</b>：预测纹理按瓦片坐标 + LOD 单独缓存，
 * 全部从未采集像素铺起；未采集的 512 分区（内存里连瓦片对象都没有）也能有预测。
 * 绘制时先铺预测、再铺地形；地形层未采集像素透明，预测从下面透出来。
 */
public final class TileLodTextures {

    static final int MAX_LOD = 5; // 512, 256, 128, 64, 32, 16
    /** 缓存预算：只算已经生成的那几级纹理，超出就按最久未用淘汰。 */
    private static final long BUDGET_BYTES = 96L * 1024 * 1024;

    private static final TileLodTextures SHARED = new TileLodTextures();

    /** 全屏地图与小地图共用同一份缓存，界面关闭不影响它。 */
    static TileLodTextures shared() {
        return SHARED;
    }

    /** 换世界/退出时清空（纹理不能再指向上一个世界的像素）。 */
    public static void clearAll() {
        SHARED.clear();
    }

    private record EntryKey(ResourceLocation dimension, String layer, int x, int z) {
    }

    private static final class Entry {
        final int revision;
        final int sharpen;
        final DynamicTexture[] terrainTextures = new DynamicTexture[MAX_LOD + 1];
        final ResourceLocation[] terrainLocations = new ResourceLocation[MAX_LOD + 1];
        long bytes;

        Entry(int revision, int sharpen) {
            this.revision = revision;
            this.sharpen = sharpen;
        }
    }

    /** 预测纹理只跟「瓦片坐标 + LOD + 群系数据版本」有关，与地形瓦片在不在内存无关。 */
    private record PredictionKey(ResourceLocation dimension, String layer, int x, int z,
                                 int level, boolean outline) {
    }

    private static final class PredictionEntry {
        final long version;
        /** {@code null} = 这块没有群系数据（缓存住，避免每帧重画）。 */
        @Nullable final ResourceLocation location;
        final long bytes;

        PredictionEntry(long version, @Nullable ResourceLocation location, long bytes) {
            this.version = version;
            this.location = location;
            this.bytes = bytes;
        }
    }

    private final Map<EntryKey, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<PredictionKey, PredictionEntry> predictions =
            new LinkedHashMap<>(64, 0.75f, true);
    private long usedBytes;
    private int textureSeq;

    private TileLodTextures() {
    }

    /** 地形层纹理（瓦片必须在内存里：它由真实的已采集像素生成）。 */
    ResourceLocation get(MapStore.TileAt at, ResourceLocation dimension, String layer,
                         int minY, int heightRange, int lod, int sharpen) {
        int level = Math.max(0, Math.min(MAX_LOD, lod));
        EntryKey key = new EntryKey(dimension, layer, at.x(), at.z());
        Entry entry = entries.get(key);
        if (entry == null || entry.revision != at.tile().revision() || entry.sharpen != sharpen) {
            release(entry);
            entry = new Entry(at.tile().revision(), sharpen);
            entries.put(key, entry);
        }
        ensureLevel(entry, at.tile(), minY, heightRange, level);
        trim();
        return entry.terrainLocations[level];
    }

    /**
     * 群系预测层纹理；没有任何预测像素时返回 {@code null}。
     *
     * <p>不看 {@link MapStore}：未采集的 512 分区照样要能铺预测，否则整片只有黑底。
     */
    @Nullable
    ResourceLocation prediction(ResourceLocation dimension, String layer, int tileX, int tileZ,
                                int lod, long version, boolean outline) {
        int level = Math.max(0, Math.min(MAX_LOD, lod));
        PredictionKey key = new PredictionKey(dimension, layer, tileX, tileZ, level, outline);
        PredictionEntry entry = predictions.get(key);
        if (entry != null && entry.version == version) {
            return entry.location;
        }
        if (entry != null) {
            releasePrediction(entry);
            predictions.remove(key);
        }
        int size = MapTile.SIZE >> level;
        NativeImage image = new NativeImage(size, size, true);
        int painted = BiomeOverlay.paint(image, dimension, tileX * MapTile.SIZE, tileZ * MapTile.SIZE,
                size, 1 << level, (x, z) -> false, outline);
        if (painted == 0) {
            image.close();
            predictions.put(key, new PredictionEntry(version, null, 0));
            return null;
        }
        DynamicTexture texture = new DynamicTexture(image);
        // 画布像素档位全是 2 的幂：每个 texel 恒为整数个画布像素，线性只会加糊。
        texture.setFilter(false, false);
        ResourceLocation location = Minecraft.getInstance().getTextureManager()
                .register("map_pack_biome_" + (textureSeq++), texture);
        long bytes = (long) size * size * 4;
        trim();
        predictions.put(key, new PredictionEntry(version, location, bytes));
        usedBytes += bytes;
        return location;
    }

    private void clear() {
        for (Entry entry : entries.values()) {
            release(entry);
        }
        entries.clear();
        for (PredictionEntry entry : predictions.values()) {
            releasePrediction(entry);
        }
        predictions.clear();
        usedBytes = 0;
    }

    /** 只淘汰最久未用的，直到回到预算内；当前视口里的瓦片刚被访问过，不会被选中。 */
    private void trim() {
        if (usedBytes <= BUDGET_BYTES) {
            return;
        }
        Iterator<Map.Entry<EntryKey, Entry>> iterator = entries.entrySet().iterator();
        while (usedBytes > BUDGET_BYTES && iterator.hasNext()) {
            Map.Entry<EntryKey, Entry> eldest = iterator.next();
            release(eldest.getValue());
            iterator.remove();
        }
        Iterator<Map.Entry<PredictionKey, PredictionEntry>> predictionIterator =
                predictions.entrySet().iterator();
        while (usedBytes > BUDGET_BYTES && predictionIterator.hasNext()) {
            Map.Entry<PredictionKey, PredictionEntry> eldest = predictionIterator.next();
            releasePrediction(eldest.getValue());
            predictionIterator.remove();
        }
    }

    private void release(@Nullable Entry entry) {
        if (entry == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        for (int level = 0; level <= MAX_LOD; level++) {
            if (entry.terrainLocations[level] != null) {
                minecraft.getTextureManager().release(entry.terrainLocations[level]);
            }
        }
        usedBytes -= entry.bytes;
    }

    private void releasePrediction(@Nullable PredictionEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.location != null) {
            Minecraft.getInstance().getTextureManager().release(entry.location);
        }
        usedBytes -= entry.bytes;
    }

    /** 该档位的纹理还没建就建（其余档位等真用到再说）。 */
    private void ensureLevel(Entry entry, MapTile tile,
                             int minY, int heightRange, int level) {
        if (entry.terrainLocations[level] != null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int size = MapTile.SIZE >> level;
        NativeImage terrain = renderTerrain(tile, minY, heightRange, level, entry.sharpen);

        DynamicTexture terrainTexture = new DynamicTexture(terrain);
        // 档位为 2 的幂时 lodSize * bpp == 512 ⇒ 一个 texel 正好一个画布像素，
        // 每级都按整数倍放大/1:1 贴合；最近邻才不会在整数放大下糊。
        terrainTexture.setFilter(false, false);
        entry.terrainTextures[level] = terrainTexture;
        entry.terrainLocations[level] = minecraft.getTextureManager()
                .register("map_pack_tile_" + (textureSeq++), terrainTexture);
        usedBytes += (long) size * size * 4;
        entry.bytes += (long) size * size * 4;
    }

    /**
     * 直接把该瓦片渲染成某一级 LOD：{@code step×step} 个源像素取已采集像素的平均色，
     * 再按配置做一次反锐化掩模（只对 level &gt; 0：1 方块/像素那级没有合并、不需要锐化）。
     * 一个已采集像素都没有的格子保持透明，预测层从下面透出来。
     */
    private static NativeImage renderTerrain(MapTile tile, int minY, int heightRange,
                                             int level, int sharpen) {
        int size = MapTile.SIZE >> level;
        int step = 1 << level;
        // 24 位 RGB；-1 = 该格一个已采集像素都没有（不透明 ARGB 是负数，不能拿 -1 之外的值当哨兵）
        int[] colors = new int[size * size];
        java.util.Arrays.fill(colors, -1);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int red = 0;
                int green = 0;
                int blue = 0;
                int count = 0;
                for (int dz = 0; dz < step; dz++) {
                    for (int dx = 0; dx < step; dx++) {
                        int localX = x * step + dx;
                        int localZ = z * step + dz;
                        if (!tile.isKnown(localX, localZ)) {
                            continue;
                        }
                        int argb = MapShade.applyShade(tile.colorAt(localX, localZ),
                                tile.heightAt(localX, localZ), minY, heightRange,
                                tile.fluidAt(localX, localZ));
                        red += (argb >> 16) & 0xFF;
                        green += (argb >> 8) & 0xFF;
                        blue += argb & 0xFF;
                        count++;
                    }
                }
                if (count == 0) {
                    continue;
                }
                colors[z * size + x] = ((red / count) << 16) | ((green / count) << 8) | (blue / count);
            }
        }
        if (level > 0 && sharpen > 0) {
            colors = unsharp(colors, size, sharpen);
        }
        NativeImage image = new NativeImage(size, size, true);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int rgb = colors[z * size + x];
                if (rgb != -1) {
                    image.setPixelRGBA(x, z, MapShade.toNativeAbgr(0xFF000000 | rgb));
                }
            }
        }
        return image;
    }

    /**
     * 反锐化掩模：{@code out = c + k*(c - 邻域均值)}，k = strength/100。
     *
     * <p>缩小时把 step×step 个方块平均成一个像素，局部对比被抹平；这一步把高频补回来，
     * 但不像「取一个代表像素」那样丢掉细节。未知格（-1）不参与邻域也不被写，避免往洞里冒光晕。
     *
     * <p>数组元素是 <b>24 位 RGB</b>（不含 alpha），{@code -1} 表示未采集。
     */
    public static int[] unsharp(int[] src, int size, int strength) {
        float k = strength / 100f;
        int[] out = src.clone();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int index = z * size + x;
                int color = src[index];
                if (color < 0) {
                    continue;
                }
                int red = 0;
                int green = 0;
                int blue = 0;
                int count = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int nz = z + dz;
                    if (nz < 0 || nz >= size) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if ((dx == 0 && dz == 0) || nx < 0 || nx >= size) {
                            continue;
                        }
                        int neighbor = src[nz * size + nx];
                        if (neighbor < 0) {
                            continue;
                        }
                        red += (neighbor >> 16) & 0xFF;
                        green += (neighbor >> 8) & 0xFF;
                        blue += neighbor & 0xFF;
                        count++;
                    }
                }
                if (count == 0) {
                    continue;
                }
                int r = clampChannel((color >> 16) & 0xFF, red / (float) count, k);
                int g = clampChannel((color >> 8) & 0xFF, green / (float) count, k);
                int b = clampChannel(color & 0xFF, blue / (float) count, k);
                out[index] = (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    private static int clampChannel(int value, float neighborAverage, float k) {
        int sharpened = Math.round(value + k * (value - neighborAverage));
        return Math.max(0, Math.min(255, sharpened));
    }
}
