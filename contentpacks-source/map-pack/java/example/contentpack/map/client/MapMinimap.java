package example.contentpack.map.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudLayoutApi;
import com.catoxide.catoxidesbattlerebuild.core.map.MapRenderContext;
import com.mojang.blaze3d.platform.NativeImage;
import example.contentpack.map.MapConfig;
import example.contentpack.map.MapHooks;
import example.contentpack.map.MapLayer;
import example.contentpack.map.MapStorage;
import example.contentpack.map.MapStore;
import example.contentpack.map.MapTile;
import example.contentpack.map.MapView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 右上角小地图。
 *
 * <p>渲染路径：{@link MapStore} 的瓦片 → 一张 {@code size×size} 的 {@link NativeImage}
 * → {@link DynamicTexture} → 一次 blit。重建只在玩家移动超过阈值或定时触发，
 * 每帧只画一个 quad，不逐像素计算。
 *
 * <p>像素读写全部发生在渲染线程（{@code RenderGuiEvent.Post}），与采集 tick 同为客户端主线程。
 */
public final class MapMinimap {

    private static final long REBUILD_INTERVAL_NANOS = 500_000_000L;
    private static final int MOVE_THRESHOLD_BLOCKS = 4;

    private static NativeImage image;
    private static DynamicTexture texture;
    private static ResourceLocation location;
    private static int textureSeq;
    private static long lastRebuildNanos;
    private static int lastCenterX = Integer.MIN_VALUE;
    private static int lastCenterZ = Integer.MIN_VALUE;
    private static ResourceLocation lastDimension;
    private static String lastLayer;
    private static int lastSize;
    private static int lastBpp;

    private MapMinimap() {
    }

    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (!MapConfig.enabled() || minecraft.player == null || level == null
                || minecraft.options.hideGui || MapWorld.id() == null) {
            return;
        }

        int size = MapConfig.minimapSize();
        int bpp = MapConfig.blocksPerPixel();
        int centerX = (int) Math.floor(minecraft.player.getX());
        int centerZ = (int) Math.floor(minecraft.player.getZ());
        ResourceLocation dimension = level.dimension().location();
        String layer = activeLayer(level);

        boolean moved = Math.abs(centerX - lastCenterX) >= MOVE_THRESHOLD_BLOCKS
                || Math.abs(centerZ - lastCenterZ) >= MOVE_THRESHOLD_BLOCKS;
        boolean expired = System.nanoTime() - lastRebuildNanos >= REBUILD_INTERVAL_NANOS;
        if (image == null || size != lastSize || bpp != lastBpp
                || !dimension.equals(lastDimension) || !layer.equals(lastLayer)
                || moved || expired) {
            rebuild(minecraft, level, dimension, layer, centerX, centerZ, size, bpp);
        }

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int x = screenWidth - size - 6 + HudLayoutApi.offsetX(MapHudElement.ID);
        int y = 6 + HudLayoutApi.offsetY(MapHudElement.ID);

        graphics.fill(x, y, x + size, y + size, 0x80101010);
        if (location != null) {
            graphics.blit(location, x, y, 0, 0, size, size, size, size);
        }

        // 扩展层（如 Sable sub-level 俯视快照）叠在地形上、边框与标记之下
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        int span = size * bpp;
        int halfSpan = span / 2; // 与 rebuild() 的整数原点保持一致
        MapRenderContext overlayContext = new MapRenderContext(graphics, false,
                x, y, size, bpp,
                centerX - halfSpan, centerZ - halfSpan,
                dimension, partialTick);
        graphics.enableScissor(x, y, x + size, y + size);
        try {
            MapHooks.INSTANCE.renderOverlays(overlayContext);
        } finally {
            graphics.disableScissor();
        }

        int border = 0xB0000000;
        graphics.fill(x - 1, y - 1, x + size + 1, y, border);
        graphics.fill(x - 1, y + size, x + size + 1, y + size + 1, border);
        graphics.fill(x - 1, y, x, y + size, border);
        graphics.fill(x + size, y, x + size + 1, y + size, border);

        int playerX = x + size / 2;
        int playerY = y + size / 2;
        graphics.fill(playerX - 1, playerY - 1, playerX + 2, playerY + 2, 0xFFFF4040);
        drawHeading(graphics, playerX, playerY, MapView.yaw(minecraft.player, partialTick));

        String coords = centerX + ", " + centerZ;
        if (MapLayer.isSection(layer)) {
            coords += "  " + MapLayer.label(layer);
        }
        int right = x + size;
        int lineY = y + size + 3;
        drawRightAligned(graphics, minecraft, coords, right, lineY, 0xCCFFFFFF);
        lineY += 10;
        if (MapConfig.showBiome()) {
            String biome = MapProbe.biomeName(level, minecraft.player.blockPosition());
            drawRightAligned(graphics, minecraft, biome, right, lineY, 0xCCFFFFFF);
            lineY += 10;
        }
        if (MapConfig.showTime()) {
            drawRightAligned(graphics, minecraft, timeString(level), right, lineY, 0xCCFFFFFF);
        }

        MarkerHud.renderMinimap(graphics, minecraft, x, y, size, bpp,
                centerX, centerZ, dimension, partialTick);
    }

    private static void drawRightAligned(GuiGraphics graphics, Minecraft minecraft,
                                         String text, int right, int y, int color) {
        graphics.drawString(minecraft.font, text,
                right - minecraft.font.width(text), y, color, true);
    }

    /** 游戏内时间 HH:MM（MC 的 0 tick 是 06:00）。 */
    private static String timeString(ClientLevel level) {
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        int minutes = (int) (((dayTime + 6000) % 24000) * 1440 / 24000);
        return String.format("%02d:%02d", (minutes / 60) % 24, minutes % 60);
    }

    /** 朝向短线：北向上，角度 0 = 南（MC 约定 -Z 为北，yaw 0 朝 +Z）。 */
    private static void drawHeading(GuiGraphics graphics, int x, int y, float yaw) {
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians);
        double dz = Math.cos(radians);
        for (int step = 3; step <= 7; step++) {
            int px = (int) Math.round(x + dx * step);
            int py = (int) Math.round(y + dz * step);
            graphics.fill(px, py, px + 1, py + 1, 0xFFFF8080);
        }
    }

    /**
     * 小地图当前看的层：地狱跟随玩家所在切片（top 在基岩天花板下永远是基岩），
     * 其他维度固定 surface。
     */
    static String activeLayer(ClientLevel level) {
        if (level.dimension() != Level.NETHER) {
            return MapLayer.TOP;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int y = minecraft.player == null ? level.getMinBuildHeight() : minecraft.player.getBlockY();
        return MapLayer.section(MapLayer.sectionStart(y, level.getMinBuildHeight()));
    }

    /** 全屏地图可切换的层：surface + 当前维度所有 16 格切片。 */
    static List<String> selectableLayers(ClientLevel level) {
        List<String> layers = new ArrayList<>();
        layers.add(MapLayer.TOP);
        layers.addAll(MapLayer.allSections(level));
        return layers;
    }

    private static void rebuild(Minecraft minecraft, ClientLevel level, ResourceLocation dimension,
                                String layer, int centerX, int centerZ, int size, int bpp) {
        releaseTexture(minecraft);

        int span = size * bpp;
        int originX = centerX - span / 2;
        int originZ = centerZ - span / 2;
        requestVisibleTiles(dimension, layer, originX, originZ, span, 64);
        boolean prediction = MapLayer.TOP.equals(layer);
        if (prediction) {
            MapBiomeLayer.ensureVisible(dimension, originX, originZ, span);
        }

        NativeImage fresh = new NativeImage(size, size, true);
        int minY = level.getMinBuildHeight();
        int heightRange = Math.max(1, level.getMaxBuildHeight() - minY - 1);

        for (int py = 0; py < size; py++) {
            int worldZ = originZ + py * bpp;
            for (int px = 0; px < size; px++) {
                int worldX = originX + px * bpp;
                int argb = MapStore.sampleColor(dimension, layer, worldX, worldZ);
                if (argb == 0) {
                    continue; // 未采集：留给预测层（BiomeOverlay）
                }
                int y = MapStore.sampleHeight(dimension, layer, worldX, worldZ);
                int fluid = MapStore.sampleFluid(dimension, layer, worldX, worldZ);
                fresh.setPixelRGBA(px, py,
                        toNativeAbgr(applyShade(argb, y, minY, heightRange, fluid)));
            }
        }

        if (prediction) {
            BiomeOverlay.paint(fresh, dimension, originX, originZ, size, bpp,
                    (px, py) -> MapStore.sampleColor(dimension, layer,
                            originX + px * bpp, originZ + py * bpp) != 0,
                    MapConfig.predictionOutline() && bpp <= 4);
        }

        image = fresh;
        texture = new DynamicTexture(fresh);
        location = minecraft.getTextureManager()
                .register("map_pack_minimap_" + (textureSeq++), texture);

        lastRebuildNanos = System.nanoTime();
        lastCenterX = centerX;
        lastCenterZ = centerZ;
        lastDimension = dimension;
        lastLayer = layer;
        lastSize = size;
        lastBpp = bpp;
    }

    /**
     * 可见范围内的瓦片若不在内存，异步从磁盘读。
     *
     * @param limit 单次请求上限；视野很大时只拉中心附近，避免拖动时 IO 风暴
     */
    static void requestVisibleTiles(ResourceLocation dimension, String layer,
                                    int originX, int originZ, int span, int limit) {
        int minTileX = MapTile.tileCoord(originX);
        int maxTileX = MapTile.tileCoord(originX + span - 1);
        int minTileZ = MapTile.tileCoord(originZ);
        int maxTileZ = MapTile.tileCoord(originZ + span - 1);
        int total = (maxTileX - minTileX + 1) * (maxTileZ - minTileZ + 1);
        if (total > limit) {
            // 只取中心附近的方形区域
            int radius = Math.max(1, (int) Math.ceil(Math.sqrt(limit) / 2.0));
            int centerX = (minTileX + maxTileX) / 2;
            int centerZ = (minTileZ + maxTileZ) / 2;
            minTileX = centerX - radius;
            maxTileX = centerX + radius;
            minTileZ = centerZ - radius;
            maxTileZ = centerZ + radius;
        }
        int requested = 0;
        for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                if (requested++ >= limit) {
                    return;
                }
                MapStorage.requestLoad(dimension, layer, tileX, tileZ);
            }
        }
    }

    private static void releaseTexture(Minecraft minecraft) {
        if (location != null) {
            minecraft.getTextureManager().release(location);
            location = null;
        }
        texture = null;
        image = null;
    }

    /**
     * 高度明暗 + 流体修正。
     * <p>世界底部 55% 亮度、顶部 100%；水按深度最多再压暗 45%，岩浆提亮 12%。
     *
     * @param fluid 正数水深、负数岩浆深度、0 非流体
     */
    static int applyShade(int argb, int y, int minY, int heightRange, int fluid) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        float t = (y - minY) / (float) heightRange;
        t = Math.max(0f, Math.min(1f, t));
        float shade = 0.55f + 0.45f * t;
        if (fluid > 0) {
            shade *= 1f - Math.min(0.45f, fluid / 64f);
        } else if (fluid < 0) {
            shade *= 1.12f;
        }
        r = Math.min(255, (int) (r * shade));
        g = Math.min(255, (int) (g * shade));
        b = Math.min(255, (int) (b * shade));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * ARGB → NativeImage 的 ABGR 内存布局。
     * <p>小端存储后字节序为 R,G,B,A，正好是 GPU 的 RGBA8 纹理格式。
     * <p>1.21.1 的 {@code NativeImage.setPixelRGBA} 实际是直存内存 int（不做 RGBA→ABGR 转换），
     * 传 RGBA 会让 R 通道被填成 alpha、整体偏红（实测），所以这里按 ABGR 传。
     */
    static int toNativeAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
