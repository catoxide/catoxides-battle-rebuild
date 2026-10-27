package example.contentpack.map.client;

import com.mojang.blaze3d.platform.NativeImage;
import example.contentpack.map.MapConfig;
import example.contentpack.map.MapTile;
import example.contentpack.map.MapStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 瓦片纹理 + 多级 LOD：像图片编辑器一样缩放。
 *
 * <p>每块 128×128 瓦片预生成 128/64/32/16 四级纹理，缩小到某个档位时直接用对应级别，
 * 而不是把 128 的纹理硬缩到 4 像素（GPU 双线性只会采 4 个点 = 「每个区域取中心」）。
 * 缩放只切换 LOD 和绘制尺寸，不重新采样，所以是瞬时的。
 *
 * <p>预测群系只画在 LOD0 上，再逐级平均下来，缩小时不会变成密集噪点。
 */
final class TileLodTextures {

    static final int MAX_LOD = 3; // 128, 64, 32, 16

    private final Map<Long, Entry> entries = new HashMap<>();
    private int textureSeq;

    private static final class Entry {
        final int revision;
        final long biomeVersion;
        final DynamicTexture[] textures;
        final ResourceLocation[] locations;
        final NativeImage[] images;

        Entry(int revision, long biomeVersion, DynamicTexture[] textures,
              ResourceLocation[] locations, NativeImage[] images) {
            this.revision = revision;
            this.biomeVersion = biomeVersion;
            this.textures = textures;
            this.locations = locations;
            this.images = images;
        }
    }

    ResourceLocation get(MapStore.TileAt at, ResourceLocation dimension,
                         int minY, int heightRange, int lod, boolean prediction,
                         long biomeVersion) {
        long key = ((long) at.x() << 32) ^ (at.z() & 0xFFFFFFFFL);
        Entry entry = entries.get(key);
        // 群系预测是异步到达的：预测版本变了必须重建，否则叠加层永远不出现
        if (entry == null || entry.revision != at.tile().revision()
                || entry.biomeVersion != biomeVersion) {
            if (entry != null) {
                release(entry);
            }
            entry = build(at, dimension, minY, heightRange, prediction, biomeVersion);
            entries.put(key, entry);
        }
        int level = Math.max(0, Math.min(MAX_LOD, lod));
        return entry.locations[level];
    }

    void clear() {
        Minecraft minecraft = Minecraft.getInstance();
        for (Entry entry : entries.values()) {
            release(entry);
        }
        entries.clear();
    }

    private void release(Entry entry) {
        Minecraft minecraft = Minecraft.getInstance();
        for (ResourceLocation location : entry.locations) {
            if (location != null) {
                minecraft.getTextureManager().release(location);
            }
        }
        for (NativeImage image : entry.images) {
            if (image != null) {
                image.close();
            }
        }
    }

    private Entry build(MapStore.TileAt at, ResourceLocation dimension,
                        int minY, int heightRange, boolean prediction, long biomeVersion) {
        Minecraft minecraft = Minecraft.getInstance();
        MapTile tile = at.tile();
        int originX = at.x() * MapTile.SIZE;
        int originZ = at.z() * MapTile.SIZE;

        NativeImage base = new NativeImage(MapTile.SIZE, MapTile.SIZE, true);
        for (int localZ = 0; localZ < MapTile.SIZE; localZ++) {
            for (int localX = 0; localX < MapTile.SIZE; localX++) {
                if (!tile.isKnown(localX, localZ)) {
                    continue;
                }
                int argb = tile.colorAt(localX, localZ);
                int y = tile.heightAt(localX, localZ);
                int fluid = tile.fluidAt(localX, localZ);
                base.setPixelRGBA(localX, localZ, MapMinimap.toNativeAbgr(
                        MapMinimap.applyShade(argb, y, minY, heightRange, fluid)));
            }
        }
        if (prediction) {
            BiomeOverlay.paint(base, dimension, originX, originZ, MapTile.SIZE, 1,
                    tile::isKnown, MapConfig.predictionOutline());
        }

        NativeImage[] images = new NativeImage[MAX_LOD + 1];
        images[0] = base;
        for (int level = 1; level <= MAX_LOD; level++) {
            images[level] = downsample(images[level - 1]);
        }

        DynamicTexture[] textures = new DynamicTexture[MAX_LOD + 1];
        ResourceLocation[] locations = new ResourceLocation[MAX_LOD + 1];
        for (int level = 0; level <= MAX_LOD; level++) {
            DynamicTexture texture = new DynamicTexture(images[level]);
            texture.setFilter(true, false);
            textures[level] = texture;
            locations[level] = minecraft.getTextureManager()
                    .register("map_pack_tile_" + (textureSeq++), texture);
        }
        return new Entry(tile.revision(), biomeVersion, textures, locations, images);
    }

    /** 2×2 平均降采样（预过滤，避免缩小时丢细节）。 */
    private static NativeImage downsample(NativeImage source) {
        int size = Math.max(1, source.getWidth() / 2);
        NativeImage target = new NativeImage(size, size, true);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                int alphaSum = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int rgba = source.getPixelRGBA(x * 2 + dx, y * 2 + dy);
                        int alpha = rgba & 0xFF;
                        r += ((rgba >> 24) & 0xFF) * alpha;
                        g += ((rgba >> 16) & 0xFF) * alpha;
                        b += ((rgba >> 8) & 0xFF) * alpha;
                        alphaSum += alpha;
                    }
                }
                // 颜色按 alpha 加权，避免透明像素把预测层拉暗/洗淡
                int alpha = alphaSum / 4;
                int outR = alphaSum == 0 ? 0 : r / alphaSum;
                int outG = alphaSum == 0 ? 0 : g / alphaSum;
                int outB = alphaSum == 0 ? 0 : b / alphaSum;
                target.setPixelRGBA(x, y,
                        (outR << 24) | (outG << 16) | (outB << 8) | alpha);
            }
        }
        return target;
    }
}
