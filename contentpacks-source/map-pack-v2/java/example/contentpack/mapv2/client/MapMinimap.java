package example.contentpack.mapv2.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudLayoutApi;
import com.catoxide.catoxidesbattlerebuild.core.map.MapRenderContext;
import com.mojang.blaze3d.platform.NativeImage;
import example.contentpack.mapv2.MapConfig;
import example.contentpack.mapv2.MapHooks;
import example.contentpack.mapv2.MapLayer;
import example.contentpack.mapv2.MapLayerSelection;
import example.contentpack.mapv2.MapLayerStack;
import example.contentpack.mapv2.MapScan;
import example.contentpack.mapv2.MapShade;
import example.contentpack.mapv2.MapStorage;
import example.contentpack.mapv2.MapStore;
import example.contentpack.mapv2.MapTile;
import example.contentpack.mapv2.MapView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        int x = screenWidth - size - 6 + HudLayoutApi.offsetX(MinimapHudElement.ID);
        int y = 6 + HudLayoutApi.offsetY(MinimapHudElement.ID);

        graphics.fill(x, y, x + size, y + size, 0x80101010);
        if (location != null) {
            graphics.blit(location, x, y, 0, 0, size, size, size, size);
        }

        // 扩展层（如 Sable sub-level 俯视快照）叠在地形上、边框与标记之下
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        int span = size * bpp;
        int halfSpan = span / 2; // 与 rebuild() 的整数原点保持一致
        MapRenderContext overlayContext = new MapRenderContext(graphics, false,
                x, y, size, size, bpp,
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
        // 玩家头像代替红点；朝向短线从头像外缘往外画，否则会被头像盖住
        MarkerHud.drawPlayerHead(graphics, minecraft, playerX, playerY, 10);
        drawHeading(graphics, playerX, playerY, MapView.yaw(minecraft.player, partialTick));

        // 信息条是独立 HUD 元素：默认贴在小地图下方，但位置只由自己的偏移决定，
        // 不再叠加小地图的偏移 —— 否则挪动小地图时文字会被一起拖走（等于还是绑在一起）。
        drawInfoLines(graphics, minecraft, level, layer, centerX, centerZ, size, screenWidth);

        MarkerHud.renderMinimap(graphics, minecraft, x, y, size, bpp,
                centerX, centerZ, dimension, partialTick);
    }

    /**
     * 信息条：每行独立开关、统一对齐、按百分比缩放。
     *
     * <p>锚点先换算成缩放前坐标再交给 {@link MinimapInfoLayout}，否则字号变大时
     * 右对齐的文字会被一起推离小地图边缘。
     */
    private static void drawInfoLines(GuiGraphics graphics, Minecraft minecraft, ClientLevel level,
                                      String layer, int centerX, int centerZ, int size, int screenWidth) {
        boolean coords = MapConfig.showCoords();
        boolean biome = MapConfig.showBiome();
        boolean time = MapConfig.showTime();
        if (MinimapInfoLayout.lineCount(coords, biome, time) == 0) {
            return;
        }
        float textScale = MapConfig.infoTextScale() / 100f;
        int align = MapConfig.infoAlign();
        int offsetX = HudLayoutApi.offsetX(MinimapInfoHudElement.ID);
        int offsetY = HudLayoutApi.offsetY(MinimapInfoHudElement.ID);
        int left = screenWidth - size - 6 + offsetX;
        int right = screenWidth - 6 + offsetX;
        int center = screenWidth - size / 2 - 6 + offsetX;
        int top = 6 + size + 3 + offsetY;

        graphics.pose().pushPose();
        graphics.pose().scale(textScale, textScale, 1f);
        int line = 0;
        if (coords) {
            String text = centerX + ", " + centerZ;
            if (MapLayer.isSection(layer)) {
                text += "  " + MapLayer.label(layer);
            }
            line = drawInfoLine(graphics, minecraft, text, align,
                    left, center, right, textScale, top, line);
        }
        if (biome) {
            line = drawInfoLine(graphics, minecraft,
                    MapProbe.biomeName(level, minecraft.player.blockPosition()), align,
                    left, center, right, textScale, top, line);
        }
        if (time) {
            drawInfoLine(graphics, minecraft, timeString(level), align,
                    left, center, right, textScale, top, line);
        }
        graphics.pose().popPose();
    }

    private static int drawInfoLine(GuiGraphics graphics, Minecraft minecraft, String text,
                                    int align, int left, int center, int right,
                                    float textScale, int top, int line) {
        int width = minecraft.font.width(text);
        int x = Math.round(MinimapInfoLayout.alignX(align, width, left, center, right) / textScale);
        int y = Math.round(top / textScale) + line * 10;
        graphics.drawString(minecraft.font, text, x, y, 0xCCFFFFFF, true);
        return line + 1;
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
        for (int step = 6; step <= 10; step++) {
            int px = (int) Math.round(x + dx * step);
            int py = (int) Math.round(y + dz * step);
            graphics.fill(px, py, px + 1, py + 1, 0xFFFF8080);
        }
    }

    /**
     * 当前看的层：手动选的层（全屏地图 / PageUp/Down）优先，
     * 否则地狱跟随玩家所在切片（top 在基岩天花板下永远是基岩），其他维度固定 surface。
     *
     * <p>选择存在静态字段里：全屏地图关掉再打开、小地图都看同一层，切换才「真的生效」。
     */
    static String activeLayer(ClientLevel level) {
        String selected = MapLayerSelection.selected();
        if (selected != null) {
            return selected;
        }
        if (level.dimension() != Level.NETHER) {
            return MapLayer.TOP;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int y = minecraft.player == null ? level.getMinBuildHeight() : minecraft.player.getBlockY();
        return MapLayer.section(MapLayer.sectionStart(y, level.getMinBuildHeight()));
    }

    /**
     * 可切换的层：surface + 磁盘/内存里有数据的切片 + 玩家当前所在的切片。
     *
     * <p>只列「有内容或正在采集」的层：切片是按玩家所在 y 采集的，
     * 把 24 个切片全摆出来只会让人点到一整屏空白。
     */
    static List<String> availableLayers(ClientLevel level) {
        ResourceLocation dimension = level.dimension().location();
        int minBuildHeight = level.getMinBuildHeight();
        int maxBuildHeight = level.getMaxBuildHeight();
        Set<String> layers = new HashSet<>();
        layers.add(MapLayer.TOP);
        layers.addAll(MapStorage.knownLayers(dimension));
        layers.addAll(MapStore.layers(dimension));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            layers.add(MapLayer.section(MapLayer.sectionStart(
                    minecraft.player.getBlockY(), minBuildHeight)));
        }
        List<String> ordered = new ArrayList<>();
        for (String layer : layers) {
            if (MapLayer.TOP.equals(layer)
                    || MapLayer.inRange(layer, minBuildHeight, maxBuildHeight)) {
                ordered.add(layer);
            }
        }
        ordered.sort(MapLayer.ORDER);
        return ordered;
    }

    private static void rebuild(Minecraft minecraft, ClientLevel level, ResourceLocation dimension,
                                String layer, int centerX, int centerZ, int size, int bpp) {
        releaseTexture(minecraft);

        int span = size * bpp;
        int originX = centerX - span / 2;
        int originZ = centerZ - span / 2;
        // 切片留空 ⇒ 逐像素往下递归：整条栈（选中层及以下）一起读盘
        List<String> stack = MapLayerStack.build(availableLayers(level), layer);
        List<String> sampleOrder = stack.reversed();
        int perLayerLimit = Math.max(1, 64 / stack.size());
        for (String stackLayer : stack) {
            requestVisibleTiles(dimension, stackLayer, originX, originZ, span, perLayerLimit);
        }
        boolean prediction = MapLayer.showsBiome(layer, level.dimension() == Level.NETHER)
                && MapConfig.showBiomePrediction();
        if (prediction) {
            MapBiomeLayer.ensureVisible(dimension, originX, originZ, span);
        }

        NativeImage fresh = new NativeImage(size, size, true);
        int minY = level.getMinBuildHeight();
        int heightRange = Math.max(1, level.getMaxBuildHeight() - minY - 1);
        int relief = MapConfig.terrainRelief();

        for (int py = 0; py < size; py++) {
            int worldZ = originZ + py * bpp;
            for (int px = 0; px < size; px++) {
                int worldX = originX + px * bpp;
                MapStore.StackSample sample =
                        MapStore.sampleStack(dimension, sampleOrder, worldX, worldZ);
                if (!sample.known()) {
                    continue; // 整条栈都没采集：留给预测层（BiomeOverlay）
                }
                int y = sample.height();
                int shaded = MapShade.applyShade(sample.color(), y, minY, heightRange,
                        sample.fluid());
                if (relief > 0 && y != MapShade.NO_HEIGHT) {
                    shaded = MapShade.applyRelief(shaded, y,
                            MapStore.sampleStack(dimension, sampleOrder, worldX - bpp, worldZ).height(),
                            MapStore.sampleStack(dimension, sampleOrder, worldX, worldZ - bpp).height(),
                            bpp, relief);
                }
                fresh.setPixelRGBA(px, py, MapShade.toNativeAbgr(shaded));
            }
        }

        if (prediction) {
            BiomeOverlay.paint(fresh, dimension, level, originX, originZ, size, bpp,
                    (px, py) -> MapStore.sampleStack(dimension, sampleOrder,
                            originX + px * bpp, originZ + py * bpp).known(),
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
     * <p>整个视口都会覆盖，按圈由内向外；磁盘上没有的瓦片由 {@link MapStorage} 的正索引挡掉，
     * 不会产生读请求，所以 {@code limit} 只限制「真正排队」的数量，而不是扫描范围。
     */
    static void requestVisibleTiles(ResourceLocation dimension, String layer,
                                    int originX, int originZ, int span, int limit) {
        if (limit <= 0) {
            return;
        }
        int minTileX = MapTile.tileCoord(originX);
        int maxTileX = MapTile.tileCoord(originX + span - 1);
        int minTileZ = MapTile.tileCoord(originZ);
        int maxTileZ = MapTile.tileCoord(originZ + span - 1);
        int centerX = (minTileX + maxTileX) / 2;
        int centerZ = (minTileZ + maxTileZ) / 2;
        int radius = Math.max(Math.max(maxTileX - centerX, centerX - minTileX),
                Math.max(maxTileZ - centerZ, centerZ - minTileZ));
        int[] requested = {0};
        MapScan.centerOut(radius, (dx, dz) -> {
            if (requested[0] >= limit) {
                return false;
            }
            int tileX = centerX + dx;
            int tileZ = centerZ + dz;
            if (tileX < minTileX || tileX > maxTileX || tileZ < minTileZ || tileZ > maxTileZ) {
                return true;
            }
            if (MapStorage.requestLoad(dimension, layer, tileX, tileZ)) {
                requested[0]++;
            }
            return true;
        });
    }

    private static void releaseTexture(Minecraft minecraft) {
        if (location != null) {
            minecraft.getTextureManager().release(location);
            location = null;
        }
        texture = null;
        image = null;
    }

}
