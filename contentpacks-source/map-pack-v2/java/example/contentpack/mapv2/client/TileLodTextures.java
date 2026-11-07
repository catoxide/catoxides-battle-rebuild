package example.contentpack.mapv2.client;

import com.mojang.blaze3d.platform.NativeImage;
import example.contentpack.mapv2.MapShade;
import example.contentpack.mapv2.MapTile;
import example.contentpack.mapv2.MapStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * 每帧绘制前调用一次：上一帧的 blit 已经提交完，这时释放上一帧淘汰的纹理才不会
     * 让本帧的 blit 指向已注销的贴图（{@code FileNotFoundException: dynamic/map_pack_tile_}）。
     */
    static void beginFrame() {
        SHARED.beginFrame0();
    }

    private record EntryKey(ResourceLocation dimension, String layer, int x, int z) {
    }

    private static final class Entry {
        final long revision;
        final int sharpen;
        final int relief;
        final DynamicTexture[] terrainTextures = new DynamicTexture[MAX_LOD + 1];
        final ResourceLocation[] terrainLocations = new ResourceLocation[MAX_LOD + 1];
        long bytes;
        /** 被绘制取用的帧号；淘汰时跳过最近两帧用过的条目，释放推迟到下一帧开头。 */
        int lastFrame;

        Entry(long revision, int sharpen, int relief) {
            this.revision = revision;
            this.sharpen = sharpen;
            this.relief = relief;
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
        /** 被绘制取用的帧号；淘汰时跳过当前帧，释放推迟到下一帧开头。 */
        int lastFrame;

        PredictionEntry(long version, @Nullable ResourceLocation location, long bytes) {
            this.version = version;
            this.location = location;
            this.bytes = bytes;
        }
    }

    private final Map<EntryKey, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<PredictionKey, PredictionEntry> predictions =
            new LinkedHashMap<>(64, 0.75f, true);
    /** 已移出缓存、但本帧还被 blit 引用的纹理；下一帧开头才 release。 */
    private final List<Entry> pendingEntries = new ArrayList<>();
    private final List<PredictionEntry> pendingPredictions = new ArrayList<>();
    private long usedBytes;
    private int textureSeq;
    private int frameSeq;

    private TileLodTextures() {
    }

    /**
     * 地形层纹理：把整条叠层合成到一张瓦片纹理（空像素露出下一层），再按缩放档出 LOD。
     * 比起逐层各画一张再叠，合成纹理只有一份内存、一共一次 blit，叠 16 个切片也不会爆。
     *
     * @param stack 从低到高的层名（{@link example.contentpack.mapv2.MapLayerStack#build} 的顺序），
     *              最后一层是选中层
     */
    ResourceLocation get(ResourceLocation dimension, List<String> stack, int tileX, int tileZ,
                         int minY, int heightRange, int lod, int sharpen, int relief) {
        int level = Math.max(0, Math.min(MAX_LOD, lod));
        String selected = stack.get(stack.size() - 1);
        EntryKey key = new EntryKey(dimension, selected, tileX, tileZ);
        long signature = signature(dimension, stack, tileX, tileZ);
        Entry entry = entries.get(key);
        if (entry == null || entry.revision != signature
                || entry.sharpen != sharpen || entry.relief != relief) {
            retire(entry);
            entry = new Entry(signature, sharpen, relief);
            entries.put(key, entry);
        }
        entry.lastFrame = frameSeq;
        ensureLevel(entry, dimension, stack, tileX, tileZ, minY, heightRange, level);
        trim();
        return entry.terrainLocations[level];
    }

    /** 叠层内容签名：任何一层瓦片更新或栈构成变化都要重建合成纹理。 */
    private static long signature(ResourceLocation dimension, List<String> stack, int tileX, int tileZ) {
        long hash = 1125899906842597L;
        for (String layer : stack) {
            MapTile tile = MapStore.get(dimension, layer, tileX, tileZ);
            hash = hash * 31 + layer.hashCode();
            hash = hash * 31 + (tile == null ? -1L : tile.revision());
        }
        return hash;
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
            entry.lastFrame = frameSeq;
            return entry.location;
        }
        if (entry != null) {
            retirePrediction(entry);
            predictions.remove(key);
        }
        int size = MapTile.SIZE >> level;
        NativeImage image = new NativeImage(size, size, true);
        int painted = BiomeOverlay.paint(image, dimension, Minecraft.getInstance().level,
                tileX * MapTile.SIZE, tileZ * MapTile.SIZE,
                size, 1 << level, (x, z) -> false, outline);
        if (painted == 0) {
            image.close();
            PredictionEntry empty = new PredictionEntry(version, null, 0);
            empty.lastFrame = frameSeq;
            predictions.put(key, empty);
            return null;
        }
        DynamicTexture texture = new DynamicTexture(image);
        // 画布像素档位全是 2 的幂：每个 texel 恒为整数个画布像素，线性只会加糊。
        texture.setFilter(false, false);
        ResourceLocation location = Minecraft.getInstance().getTextureManager()
                .register("map_pack_biome_" + (textureSeq++), texture);
        long bytes = (long) size * size * 4;
        trim();
        PredictionEntry created = new PredictionEntry(version, location, bytes);
        created.lastFrame = frameSeq;
        predictions.put(key, created);
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
        for (Entry entry : pendingEntries) {
            freeTextures(entry);
        }
        pendingEntries.clear();
        for (PredictionEntry entry : pendingPredictions) {
            freePrediction(entry);
        }
        pendingPredictions.clear();
        usedBytes = 0;
    }

    /** 释放上一帧淘汰掉的纹理；blit 已经提交完，这时注销才不会让本帧指向空贴图。 */
    private void beginFrame0() {
        frameSeq++;
        for (Entry entry : pendingEntries) {
            freeTextures(entry);
        }
        pendingEntries.clear();
        for (PredictionEntry entry : pendingPredictions) {
            freePrediction(entry);
        }
        pendingPredictions.clear();
    }

    /**
     * 淘汰到预算内。最近两帧用过的条目跳过——现在释放会让同帧后面的 blit 指向已注销的
     * 贴图（{@code FileNotFoundException: dynamic/map_pack_tile_}），也避免视口里的瓦片
     * 在遍历到之前被踢掉又重建。它们留给下一帧开头。
     */
    private void trim() {
        if (usedBytes <= BUDGET_BYTES) {
            return;
        }
        long protectFrom = frameSeq - 1;
        Iterator<Map.Entry<EntryKey, Entry>> iterator = entries.entrySet().iterator();
        while (usedBytes > BUDGET_BYTES && iterator.hasNext()) {
            Entry eldest = iterator.next().getValue();
            if (protectFrom > 0 && eldest.lastFrame >= protectFrom) {
                continue;
            }
            usedBytes -= eldest.bytes;
            pendingEntries.add(eldest);
            iterator.remove();
        }
        Iterator<Map.Entry<PredictionKey, PredictionEntry>> predictionIterator =
                predictions.entrySet().iterator();
        while (usedBytes > BUDGET_BYTES && predictionIterator.hasNext()) {
            PredictionEntry eldest = predictionIterator.next().getValue();
            if (protectFrom > 0 && eldest.lastFrame >= protectFrom) {
                continue;
            }
            usedBytes -= eldest.bytes;
            pendingPredictions.add(eldest);
            predictionIterator.remove();
        }
    }

    /** 旧纹理退休：本帧已经用过的推迟释放。 */
    private void retire(@Nullable Entry entry) {
        if (entry == null) {
            return;
        }
        if (frameSeq > 0 && entry.lastFrame == frameSeq) {
            usedBytes -= entry.bytes;
            pendingEntries.add(entry);
        } else {
            release(entry);
        }
    }

    private void retirePrediction(@Nullable PredictionEntry entry) {
        if (entry == null) {
            return;
        }
        if (frameSeq > 0 && entry.lastFrame == frameSeq) {
            usedBytes -= entry.bytes;
            pendingPredictions.add(entry);
        } else {
            releasePrediction(entry);
        }
    }

    private void release(@Nullable Entry entry) {
        if (entry == null) {
            return;
        }
        freeTextures(entry);
        usedBytes -= entry.bytes;
    }

    /** 只注销贴图，不动字节计数（计数在移出缓存时已经扣过）。 */
    private void freeTextures(Entry entry) {
        Minecraft minecraft = Minecraft.getInstance();
        for (int level = 0; level <= MAX_LOD; level++) {
            if (entry.terrainLocations[level] != null) {
                minecraft.getTextureManager().release(entry.terrainLocations[level]);
            }
        }
    }

    private void releasePrediction(@Nullable PredictionEntry entry) {
        if (entry == null) {
            return;
        }
        freePrediction(entry);
        usedBytes -= entry.bytes;
    }

    private void freePrediction(PredictionEntry entry) {
        if (entry.location != null) {
            Minecraft.getInstance().getTextureManager().release(entry.location);
        }
    }

    /** 该档位的纹理还没建就建（其余档位等真用到再说）。 */
    private void ensureLevel(Entry entry, ResourceLocation dimension, List<String> stack,
                             int tileX, int tileZ, int minY, int heightRange, int level) {
        if (entry.terrainLocations[level] != null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int size = MapTile.SIZE >> level;
        // 立体光暗要读西/北邻格高度，瓦片最外一圈得从相邻瓦片取，否则每 512 方块一条反光缝
        int layers = stack.size();
        MapTile[] tiles = new MapTile[layers];
        MapTile[] west = new MapTile[layers];
        MapTile[] north = new MapTile[layers];
        for (int i = 0; i < layers; i++) {
            String layer = stack.get(i);
            tiles[i] = MapStore.get(dimension, layer, tileX, tileZ);
            west[i] = MapStore.get(dimension, layer, tileX - 1, tileZ);
            north[i] = MapStore.get(dimension, layer, tileX, tileZ - 1);
        }
        NativeImage terrain = renderTerrain(tiles, west, north, minY, heightRange,
                level, entry.sharpen, entry.relief);

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
     * 把整条叠层渲染成某一级 LOD：{@code step×step} 个源像素先按「从高到低找第一个已采集层」
     * 合成（空像素透出下层），再取平均色；接着按配置做反锐化掩模（只对 level &gt; 0），
     * 最后按相邻格高度做立体光暗。整条栈都没采集的格子保持透明，预测层从下面透出来。
     *
     * @param stack 从低到高的瓦片（与 {@link #get} 的层顺序一致），高层的像素覆盖低层
     * @param west  西侧相邻瓦片栈，没有就传 {@code null}（最西一列退化为只看南北向坡度）
     * @param north 北侧相邻瓦片栈，同上
     */
    private static NativeImage renderTerrain(MapTile[] stack, @Nullable MapTile[] west,
                                             @Nullable MapTile[] north,
                                             int minY, int heightRange, int level,
                                             int sharpen, int relief) {
        int size = MapTile.SIZE >> level;
        int step = 1 << level;
        // 24 位 RGB；-1 = 该格一个已采集像素都没有（不透明 ARGB 是负数，不能拿 -1 之外的值当哨兵）
        int[] colors = new int[size * size];
        java.util.Arrays.fill(colors, -1);
        // 每格已采集像素的平均高度；立体光暗用。未采集 = NO_HEIGHT
        int[] heights = new int[size * size];
        java.util.Arrays.fill(heights, MapShade.NO_HEIGHT);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int red = 0;
                int green = 0;
                int blue = 0;
                long heightSum = 0;
                int count = 0;
                for (int dz = 0; dz < step; dz++) {
                    for (int dx = 0; dx < step; dx++) {
                        int localX = x * step + dx;
                        int localZ = z * step + dz;
                        MapTile source = sampleLayer(stack, localX, localZ);
                        if (source == null) {
                            continue;
                        }
                        int height = source.heightAt(localX, localZ);
                        int argb = MapShade.applyShade(source.colorAt(localX, localZ),
                                height, minY, heightRange, source.fluidAt(localX, localZ));
                        red += (argb >> 16) & 0xFF;
                        green += (argb >> 8) & 0xFF;
                        blue += argb & 0xFF;
                        heightSum += height;
                        count++;
                    }
                }
                if (count == 0) {
                    continue;
                }
                heights[z * size + x] = (int) (heightSum / count);
                colors[z * size + x] = ((red / count) << 16) | ((green / count) << 8) | (blue / count);
            }
        }
        if (level > 0 && sharpen > 0) {
            colors = unsharp(colors, size, sharpen);
        }
        if (relief > 0) {
            applyRelief(colors, heights, west, north, size, step, relief);
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

    /** 叠层采样：从高到低找第一个已采集像素所在的层；整条栈都空返回 {@code null}。 */
    @Nullable
    private static MapTile sampleLayer(MapTile[] stack, int localX, int localZ) {
        for (int i = stack.length - 1; i >= 0; i--) {
            MapTile tile = stack[i];
            if (tile != null && tile.isKnown(localX, localZ)) {
                return tile;
            }
        }
        return null;
    }

    /**
     * 立体光暗：西北侧更高 = 迎光提亮。西/北邻格优先取本瓦片内的左/上一格，
     * 最外一圈取相邻瓦片，避免每 512 方块出现一条光暗断缝。
     */
    private static void applyRelief(int[] colors, int[] heights,
                                    @Nullable MapTile[] west, @Nullable MapTile[] north,
                                    int size, int step, int relief) {
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int index = z * size + x;
                int rgb = colors[index];
                int height = heights[index];
                if (rgb < 0 || height == MapShade.NO_HEIGHT) {
                    continue;
                }
                int westHeight;
                if (x > 0) {
                    westHeight = heights[index - 1];
                } else if (west != null) {
                    westHeight = cellHeight(west, MapTile.SIZE - step, z * step, step);
                } else {
                    westHeight = MapShade.NO_HEIGHT;
                }
                int northHeight;
                if (z > 0) {
                    northHeight = heights[index - size];
                } else if (north != null) {
                    northHeight = cellHeight(north, x * step, MapTile.SIZE - step, step);
                } else {
                    northHeight = MapShade.NO_HEIGHT;
                }
                colors[index] = MapShade.applyRelief(0xFF000000 | rgb, height,
                        westHeight, northHeight, step, relief) & 0xFFFFFF;
            }
        }
    }

    /** 相邻瓦片边界格在某一级 LOD 下的平均高度（按叠层从高到低找第一块有像素的层）。 */
    private static int cellHeight(@Nullable MapTile[] stack, int localXStart, int localZStart, int step) {
        if (stack == null) {
            return MapShade.NO_HEIGHT;
        }
        for (int i = stack.length - 1; i >= 0; i--) {
            MapTile tile = stack[i];
            if (tile == null) {
                continue;
            }
            long sum = 0;
            int count = 0;
            for (int dz = 0; dz < step; dz++) {
                for (int dx = 0; dx < step; dx++) {
                    int localX = localXStart + dx;
                    int localZ = localZStart + dz;
                    if (localX < 0 || localX >= MapTile.SIZE
                            || localZ < 0 || localZ >= MapTile.SIZE
                            || !tile.isKnown(localX, localZ)) {
                        continue;
                    }
                    sum += tile.heightAt(localX, localZ);
                    count++;
                }
            }
            if (count > 0) {
                return (int) (sum / count);
            }
        }
        return MapShade.NO_HEIGHT;
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
