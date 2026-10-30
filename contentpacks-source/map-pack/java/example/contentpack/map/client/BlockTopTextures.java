package example.contentpack.map.client;

import example.contentpack.map.MapStore;
import example.contentpack.map.MapTile;
import example.contentpack.map.MapProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 最深几档缩放下的「方块顶面纹理」层。
 *
 * <p>地图瓦片本来就按像素存了方块 ID（{@link MapTile#blockAt}），放大到每方块十几个像素时
 * 直接把该方块模型朝上的那面贴图铺在格子位置，比一个纯色方块好看得多。
 *
 * <p><b>颜色不丢</b>：贴图用顶点色乘一次「瓦片记录的最终颜色 ÷ 贴图平均色」——
 * 于是纹理细节回来了，而群系 tint、山体阴影这些记录在瓦片里的信息也还成立；
 * 若贴图自带颜色（石头等），这个系数≈1，相当于原样画。
 *
 * <p><b>只画有的</b>：底层平面色瓦片先画，这里只覆盖「有顶面贴图」的方块；
 * 花、草、火把这类十字模型没有朝上的一面，流体也有自己的渲染，它们保持平面色。
 */
public final class BlockTopTextures {

    /**
     * 每方块至少 16 个 GUI 像素（{@code bpp <= 1/16}）才画纹理：
     * 再浅的档位一格塞不下 16×16 的贴图，只是多几万次 blit。
     */
    static final double MAX_BPP = 1.0 / 16.0;

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create(0L);

    /** {@code sprite == null} = 这个方块没有可用的顶面贴图（十字模型 / 流体 / 未知方块），照样缓存住。 */
    private record Entry(@Nullable TextureAtlasSprite sprite,
                         float avgR, float avgG, float avgB) {
        boolean usable() {
            return sprite != null && (avgR + avgG + avgB) > 0.03f;
        }
    }

    private BlockTopTextures() {
    }

    static boolean applies(double bpp) {
        return bpp <= MAX_BPP;
    }

    /** 贴图与模型都在资源重载时换了，缓存必须跟着清。 */
    static PreparableReloadListener reloadListener() {
        return new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager manager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void value, ResourceManager manager, ProfilerFiller profiler) {
                CACHE.clear();
            }
        };
    }

    static void render(GuiGraphics graphics, int left, int top, int view,
                       List<MapStore.TileAt> tiles,
                       double minWorldX, double minWorldZ, double bpp,
                       int minY, int heightRange) {
        if (!applies(bpp) || tiles.isEmpty()) {
            return;
        }
        Map<Long, MapTile> byTile = new HashMap<>(tiles.size() * 2);
        for (MapStore.TileAt at : tiles) {
            byTile.put(tileKey(at.x(), at.z()), at.tile());
        }

        int minBlockX = (int) Math.floor(minWorldX);
        int minBlockZ = (int) Math.floor(minWorldZ);
        int maxBlockX = (int) Math.ceil(minWorldX + view * bpp);
        int maxBlockZ = (int) Math.ceil(minWorldZ + view * bpp);
        for (int blockZ = minBlockZ; blockZ <= maxBlockZ; blockZ++) {
            int py0 = top + MapProjection.pixel(blockZ, minWorldZ, bpp);
            int py1 = top + MapProjection.pixel(blockZ + 1, minWorldZ, bpp);
            if (py1 <= top || py0 >= top + view) {
                continue;
            }
            for (int blockX = minBlockX; blockX <= maxBlockX; blockX++) {
                MapTile column = tileFor(byTile, blockX, blockZ);
                if (column == null) {
                    continue;
                }
                int localX = MapTile.localCoord(blockX);
                int localZ = MapTile.localCoord(blockZ);
                if (!column.isKnown(localX, localZ) || column.fluidAt(localX, localZ) != 0) {
                    continue;
                }
                String blockId = column.blockAt(localX, localZ);
                if (blockId == null) {
                    continue;
                }
                Entry entry = entry(blockId);
                if (entry == null || !entry.usable()) {
                    continue;
                }
                int px0 = left + MapProjection.pixel(blockX, minWorldX, bpp);
                int px1 = left + MapProjection.pixel(blockX + 1, minWorldX, bpp);
                if (px1 <= left || px0 >= left + view) {
                    continue;
                }
                float[] tint = tintOf(column.colorAt(localX, localZ),
                        column.heightAt(localX, localZ), minY, heightRange, entry);
                graphics.blit(px0, py0, 0,
                        Math.max(1, px1 - px0), Math.max(1, py1 - py0),
                        entry.sprite(), tint[0], tint[1], tint[2], 1.0f);
            }
        }
    }

    private static long tileKey(int tileX, int tileZ) {
        return ((long) tileX << 32) ^ (tileZ & 0xFFFFFFFFL);
    }

    @Nullable
    private static MapTile tileFor(Map<Long, MapTile> byTile, int blockX, int blockZ) {
        return byTile.get(tileKey(MapTile.tileCoord(blockX), MapTile.tileCoord(blockZ)));
    }

    /**
     * 顶点色 = 记录色 ÷ 贴图平均色（让「贴图均值」回到地图上原本的颜色）。
     * 某个通道会超过 1 时整体等比压暗，避免偏色。
     */
    private static float[] tintOf(int argb, int height, int minY, int heightRange, Entry entry) {
        int shaded = MapMinimap.applyShade(argb, height, minY, heightRange, 0);
        float r = ((shaded >> 16) & 0xFF) / 255f;
        float g = ((shaded >> 8) & 0xFF) / 255f;
        float b = (shaded & 0xFF) / 255f;
        r /= entry.avgR();
        g /= entry.avgG();
        b /= entry.avgB();
        float peak = Math.max(r, Math.max(g, b));
        if (peak > 1.0f) {
            r /= peak;
            g /= peak;
            b /= peak;
        }
        return new float[]{clamp01(r), clamp01(g), clamp01(b)};
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    @Nullable
    private static Entry entry(String blockId) {
        Entry cached = CACHE.get(blockId);
        if (cached != null) {
            return cached;
        }
        Entry resolved = resolve(blockId);
        CACHE.put(blockId, resolved);
        return resolved;
    }

    private static Entry resolve(String blockId) {
        Entry missing = new Entry(null, 0f, 0f, 0f);
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return missing;
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        BlockState state;
        try {
            state = block.defaultBlockState();
        } catch (RuntimeException ex) {
            return missing;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return missing;
        }
        BakedModel model = minecraft.getBlockRenderer().getBlockModel(state);
        // 顶面贴图不看模型数据/渲染类型：这里只要「有没有一面朝上的贴图」
        List<BakedQuad> quads = model.getQuads(state, Direction.UP, RANDOM,
                ModelData.EMPTY, null);
        if (quads.isEmpty()) {
            return missing;
        }
        TextureAtlasSprite sprite = quads.get(0).getSprite();
        int width = Math.max(1, sprite.contents().width());
        int height = Math.max(1, sprite.contents().height());
        long red = 0;
        long green = 0;
        long blue = 0;
        long weight = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = sprite.getPixelRGBA(0, x, y);
                int alpha = (pixel >>> 24) & 0xFF;
                if (alpha < 8) {
                    continue;
                }
                red += (long) (pixel & 0xFF) * alpha;
                green += (long) ((pixel >>> 8) & 0xFF) * alpha;
                blue += (long) ((pixel >>> 16) & 0xFF) * alpha;
                weight += alpha;
            }
        }
        if (weight == 0L) {
            return missing;
        }
        // 平均色太暗时除法会把纹理噪声放大成纯色噪点，直接当没有贴图
        float avgR = Math.max(0.02f, red / (float) (weight * 255L));
        float avgG = Math.max(0.02f, green / (float) (weight * 255L));
        float avgB = Math.max(0.02f, blue / (float) (weight * 255L));
        return new Entry(sprite, avgR, avgG, avgB);
    }

    /** 换世界：方块 ID 一样但模型/贴图可能来自不同资源环境。 */
    public static void invalidate() {
        CACHE.clear();
    }
}
