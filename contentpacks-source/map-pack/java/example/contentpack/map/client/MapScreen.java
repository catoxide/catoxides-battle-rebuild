package example.contentpack.map.client;

import example.contentpack.map.MapConfig;
import example.contentpack.map.MapLayer;
import example.contentpack.map.MapStorage;
import example.contentpack.map.MapStore;
import example.contentpack.map.MapTile;
import example.contentpack.map.MapProjection;
import example.contentpack.map.marker.MarkerData;
import example.contentpack.map.marker.MarkerDto;
import example.contentpack.map.marker.MarkerType;
import example.contentpack.map.network.MarkerCreatePayload;
import example.contentpack.map.network.MarkerRemovePayload;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全屏世界地图：源瓦片 + GPU 缩放（图片编辑器模型）+ 工具栏 UI。
 *
 * <p>底部：标记管理 / 放置 / 图层 / 设置；右侧：缩放、归中、导出；右上角显示当前缩放。
 * 按钮有悬停/按下亮度反馈；鼠标悬停标记会高亮。
 */
public final class MapScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * 缩放档位，单位「方块 / GUI 像素」，升序（index 0 = 放得最大）。
     *
     * <p>小于 1 的档位是「一个方块占多个像素」：0.5 = 每块 2×2 像素（最近邻，硬边），
     * 一直到 1/32 = 每块 32×32 像素（一屏约 16 个方块）。
     */
    private static final double[] ZOOMS = {
            1.0 / 32, 1.0 / 16, 1.0 / 8, 1.0 / 4, 1.0 / 2,
            1, 2, 4, 8, 16, 32};
    private static final int MAX_LOAD_REQUESTS = 320;
    private static final long STATS_LOG_NANOS = 5_000_000_000L;
    private static final int MARGIN = 40;
    private static final int BOTTOM_BAR = 28;
    private static final int LAYER_PANEL_WIDTH = 200;
    private static final int LAYER_ROW_HEIGHT = 18;
    private static final int LAYER_VISIBLE_ROWS = 9;
    private static final int LAYER_PANEL_PAD = 20;
    private static final int MENU_ROW = 16;

    private final TileLodTextures textures = TileLodTextures.shared();
    private final List<MapUiButton> buttons = new ArrayList<>();

    private int zoomIndex;
    private double centerX;
    private double centerZ;
    private boolean followPlayer = true;
    private boolean placing;

    private boolean pressing;
    private boolean moved;
    private double pressX;
    private double pressY;
    private MarkerDto pressedMarker;
    private boolean mouseDown;

    private String lastLayer;
    private ResourceLocation lastDimension;
    private boolean layersOpen;
    private int layerScroll;
    private boolean menuOpen;
    private int menuX;
    private int menuY;
    private int menuWorldX;
    private int menuWorldZ;
    private final List<MenuEntry> menuEntries = new ArrayList<>();
    /** 打开面板时快照一次每层瓦片数（索引遍历不必每帧做）。 */
    private final Map<String, Integer> layerCounts = new HashMap<>();

    private record MenuEntry(Component label, Runnable action) {
    }
    private long lastLoadRequestNanos;
    private int lastLoadRequestZoom = -1;
    private double lastLoadRequestX = Double.NaN;
    private double lastLoadRequestZ = Double.NaN;
    private int countMemory;
    private int countDisk;
    private int countLoading;
    private int countMissing;
    private int countCoverage;
    private long lastStatsLogNanos;

    public MapScreen() {
        super(Component.translatable("catoxidesbattlerebuild.map.title"));
        int initial = Math.max(1, MapConfig.fullscreenBlocks() / 512);
        this.zoomIndex = nearestZoomIndex(initial);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
    }

    @Override
    protected void init() {
        syncView();
        buildButtons();
    }

    private void buildButtons() {
        buttons.clear();
        int barY = this.height - BOTTOM_BAR + 4;
        int bx = 10;
        buttons.add(new MapUiButton(bx, barY, 90, 20, "Markers",
                () -> Minecraft.getInstance().setScreen(new MarkerListScreen())));
        bx += 96;
        buttons.add(new MapUiButton(bx, barY, 90, 20, placing ? "Placing..." : "Place", this::togglePlacing));
        bx += 96;
        buttons.add(new MapUiButton(bx, barY, 90, 20, "Layers", this::toggleLayers));
        bx += 96;
        buttons.add(new MapUiButton(bx, barY, 90, 20, "Settings", () -> hint("Settings: coming soon")));

        int sideX = this.width - 28;
        int sideY = top() + 10;
        buttons.add(new MapUiButton(sideX, sideY, 22, 22, "+", this::zoomIn));
        buttons.add(new MapUiButton(sideX, sideY + 26, 22, 22, "-", this::zoomOut));
        buttons.add(new MapUiButton(sideX, sideY + 52, 22, 22, "C", this::recenter));
        buttons.add(new MapUiButton(sideX, sideY + 78, 22, 22, "E", () -> hint("Export: coming soon")));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || MapWorld.id() == null) {
            graphics.fill(0, 0, this.width, this.height, 0xD0101010);
            graphics.drawCenteredString(minecraft.font,
                    Component.translatable("catoxidesbattlerebuild.map.waiting_world"),
                    this.width / 2, this.height / 2, 0xFFFFFFFF);
            return;
        }
        syncView();

        int view = viewSize();
        int left = left();
        int top = top();
        double bpp = ZOOMS[zoomIndex];
        double span = view * bpp;
        // 视图原点吸附到像素格点：方块边缘 / 标记 / 瓦片共用同一个精确映射
        double minWorldX = MapProjection.origin(centerX, view, bpp);
        double minWorldZ = MapProjection.origin(centerZ, view, bpp);
        String layer = currentLayer(level);
        ResourceLocation dimension = level.dimension().location();
        // 层选择器打开时（Layers 模式）也藏掉群系层：地表群系铺在切片下面纯属干扰
        boolean prediction = MapLayer.showsBiome(layer) && MapConfig.showBiomePrediction()
                && !layersOpen;
        int lod = lodFor(bpp);

        int minTileX = MapTile.tileCoord((int) Math.floor(minWorldX));
        int maxTileX = MapTile.tileCoord((int) Math.floor(minWorldX + span));
        int minTileZ = MapTile.tileCoord((int) Math.floor(minWorldZ));
        int maxTileZ = MapTile.tileCoord((int) Math.floor(minWorldZ + span));

        // 缩得很远时视口内可能有几万块瓦片：请求按「视图变化或 250ms 一次」节流，
        // 否则每帧扫一遍可见矩形只是一遍遍重复同样的索引查询。
        long now = System.nanoTime();
        boolean requestDue = zoomIndex != lastLoadRequestZoom
                || Math.abs(centerX - lastLoadRequestX) >= 16
                || Math.abs(centerZ - lastLoadRequestZ) >= 16
                || now - lastLoadRequestNanos >= 250_000_000L;
        if (requestDue) {
            MapMinimap.requestVisibleTiles(dimension, layer,
                    (int) Math.floor(minWorldX), (int) Math.floor(minWorldZ),
                    (int) Math.ceil(span) + 256, MAX_LOAD_REQUESTS);
            countTiles(dimension, layer, minTileX, maxTileX, minTileZ, maxTileZ);
            logStats(now);
            lastLoadRequestNanos = now;
            lastLoadRequestZoom = zoomIndex;
            lastLoadRequestX = centerX;
            lastLoadRequestZ = centerZ;
        }
        if (prediction) {
            MapBiomeLayer.ensureVisible(dimension,
                    (int) Math.floor(minWorldX), (int) Math.floor(minWorldZ),
                    (int) Math.ceil(span) + 256);
        }

        graphics.fill(0, 0, this.width, this.height, 0xD0101010);
        graphics.fill(left - 1, top - 1, left + view + 1, top + view + 1, 0xFF000000);

        int minY = level.getMinBuildHeight();
        int heightRange = Math.max(1, level.getMaxBuildHeight() - minY - 1);
        int lodSize = MapTile.SIZE >> lod;
        int screenTile = (int) Math.round(MapTile.SIZE / bpp);

        List<MapStore.TileAt> tiles = MapStore.visibleTiles(
                dimension, layer, minTileX, maxTileX, minTileZ, maxTileZ);
        long biomeVersion = prediction ? MapBiomeLayer.version() : 0L;

        graphics.enableScissor(left, top, left + view, top + view);
        // 第一遍：群系预测层——按视口矩形整片铺，未采集的分区也要有（瓦片不存在也得画）
        if (prediction) {
            boolean outline = MapConfig.predictionOutline();
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                    ResourceLocation texture = textures.prediction(dimension, layer,
                            tileX, tileZ, lod, biomeVersion, outline);
                    if (texture != null) {
                        blitTile(graphics, texture, tileX, tileZ, left, top,
                                minWorldX, minWorldZ, bpp, screenTile, lodSize);
                    }
                }
            }
        }
        // 第二遍：真实地形层（未采集像素透明，露出下面的预测层）
        int sharpen = MapConfig.zoomSharpen();
        for (MapStore.TileAt at : tiles) {
            blitTile(graphics, textures.get(at, dimension, layer, minY, heightRange, lod, sharpen),
                    at.x(), at.z(), left, top,
                    minWorldX, minWorldZ, bpp, screenTile, lodSize);
        }
        // 第三遍：放大到「一个方块好几个像素」时，用各方块自己的顶面贴图盖掉平面色
        BlockTopTextures.render(graphics, left, top, view, tiles,
                minWorldX, minWorldZ, bpp, minY, heightRange);
        graphics.disableScissor();
        drawTileStats(graphics, minecraft, left, top);

        MarkerHud.renderFullmap(graphics, minecraft, left, top, view, bpp,
                minWorldX, minWorldZ, dimension, partialTick);
        drawPlayerMarker(graphics, minecraft, left, top, view, minWorldX, minWorldZ, bpp);
        highlightHoveredMarker(graphics, minecraft, mouseX, mouseY, left, top, minWorldX, minWorldZ, bpp);

        if (layersOpen) {
            renderLayerPanel(graphics, level, mouseX, mouseY);
        }

        // 工具栏
        graphics.fill(0, this.height - BOTTOM_BAR, this.width, this.height, 0xC0101010);
        for (MapUiButton button : buttons) {
            button.render(graphics, minecraft.font, mouseX, mouseY, mouseDown);
        }
        drawZoomBox(graphics, minecraft, top);
        if (placing) {
            graphics.drawCenteredString(minecraft.font,
                    "Click the map to place a permanent marker",
                    this.width / 2, top - 14, 0xFFFFCC44);
        }

        String info = (int) Math.floor(centerX) + ", " + (int) Math.floor(centerZ)
                + "   (" + Math.round(span) + " blocks)"
                + "   [" + MapLayer.label(layer) + "]";
        graphics.drawCenteredString(minecraft.font, info, this.width / 2,
                top + view + 8, 0xFFFFFFFF);
        drawStatusLine(graphics, minecraft, level, layer, mouseX, mouseY);

        if (menuOpen) {
            renderMenu(graphics, minecraft, mouseX, mouseY);
        }
    }

    /**
     * 悬停方块信息固定在底栏上方居中（不再跟着鼠标飘），右键菜单打开时改显示菜单目标坐标。
     */
    private void drawStatusLine(GuiGraphics graphics, Minecraft minecraft, ClientLevel level,
                                String layer, int mouseX, int mouseY) {
        int lineY = top() + viewSize() + 22;
        String text = null;
        if (menuOpen) {
            text = menuWorldX + ", " + menuWorldZ;
        } else if (MapConfig.showBlockInfo() && !pressing && insideView(mouseX, mouseY)) {
            double bpp = ZOOMS[zoomIndex];
            double minWorldX = MapProjection.origin(centerX, viewSize(), bpp);
            double minWorldZ = MapProjection.origin(centerZ, viewSize(), bpp);
            int worldX = (int) Math.floor(minWorldX + (mouseX - left()) * bpp);
            int worldZ = (int) Math.floor(minWorldZ + (mouseY - top()) * bpp);
            MapProbe.Info probe = MapProbe.probe(level, worldX, worldZ, layer);
            if (probe != null) {
                text = probeText(probe);
            }
        }
        if (text == null) {
            graphics.drawCenteredString(minecraft.font,
                    Component.translatable("catoxidesbattlerebuild.map.hint"),
                    this.width / 2, lineY, 0xFFAAAAAA);
        } else {
            graphics.drawCenteredString(minecraft.font, text, this.width / 2, lineY, 0xFFFFFFFF);
        }
    }

    private static String probeText(MapProbe.Info probe) {
        StringBuilder text = new StringBuilder();
        text.append(probe.x()).append(", ").append(probe.z());
        if (probe.y() != Integer.MIN_VALUE) {
            text.append("  y=").append(probe.y());
        }
        if (probe.block() != null) {
            text.append("  ").append(probe.block());
        }
        if (probe.biome() != null) {
            text.append(probe.fromMap() ? "  ~" : "  ").append(probe.biome());
        }
        return text.toString();
    }

    /**
     * 视口内每块瓦片现在到哪一步了：内存 / 磁盘待读 / 读盘中 / 磁盘也没有。
     * 用来区分「区块没被采集」和「采了但没读回来」。
     */
    private void countTiles(ResourceLocation dimension, String layer,
                            int minTileX, int maxTileX, int minTileZ, int maxTileZ) {
        int memory = 0;
        int disk = 0;
        int loading = 0;
        int missing = 0;
        long knownPixels = 0;
        long totalPixels = 0;
        for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                MapTile tile = MapStore.get(dimension, layer, tileX, tileZ);
                if (tile != null) {
                    memory++;
                    knownPixels += tile.knownCount();
                    totalPixels += (long) MapTile.SIZE * MapTile.SIZE;
                    continue;
                }
                switch (MapStorage.status(dimension, layer, tileX, tileZ)) {
                    case DISK -> disk++;
                    case LOADING -> loading++;
                    default -> missing++;
                }
            }
        }
        countMemory = memory;
        countDisk = disk;
        countLoading = loading;
        countMissing = missing;
        countCoverage = totalPixels == 0 ? 0 : (int) (knownPixels * 100 / totalPixels);
    }

    private void drawTileStats(GuiGraphics graphics, Minecraft minecraft, int left, int top) {
        String text = "mem " + countMemory + "  disk " + countDisk
                + "  load " + countLoading + "  miss " + countMissing
                + "  cov " + countCoverage + "%"
                + (MapStorage.indexReady() ? "" : "  (index building)");
        int width = minecraft.font.width(text) + 8;
        graphics.fill(left + 4, top + 4, left + 4 + width, top + 16, 0xB0000000);
        graphics.drawString(minecraft.font, text, left + 8, top + 6, 0xFFE0E0E0, false);
    }

    /** 地图开着时每 5 秒把视口统计写一行日志，方便事后对着 latest.log 判断卡在哪。 */
    private void logStats(long now) {
        if (countMissing + countDisk + countLoading == 0) {
            return;
        }
        if (now - lastStatsLogNanos < STATS_LOG_NANOS) {
            return;
        }
        lastStatsLogNanos = now;
        LOGGER.info("[MapPack] view: mem={} cov={}% disk={} load={} miss={} indexed={}",
                countMemory, countCoverage, countDisk, countLoading, countMissing,
                MapStorage.indexedTiles());
    }

    private void drawZoomBox(GuiGraphics graphics, Minecraft minecraft, int top) {
        int x = this.width - 96;
        int y = top + 10;
        graphics.fill(x, y, x + 62, y + 18, 0xC0101010);
        graphics.drawCenteredString(minecraft.font, zoomLabel(),
                x + 31, y + 5, 0xFFFFFFFF);
    }

    /** {@code <1} 的档位用「每方块几像素」更好读，否则沿用「几方块一像素」。 */
    private String zoomLabel() {
        double bpp = ZOOMS[zoomIndex];
        if (bpp >= 1.0) {
            return (int) Math.round(bpp) + " b/px";
        }
        return Math.round(1.0 / bpp) + " px/b";
    }

    private void highlightHoveredMarker(GuiGraphics graphics, Minecraft minecraft,
                                        int mouseX, int mouseY, int left, int top,
                                        double minWorldX, double minWorldZ, double bpp) {
        if (!insideView(mouseX, mouseY)) {
            return;
        }
        MarkerDto hovered = markerAt(mouseX, mouseY);
        if (hovered == null) {
            return;
        }
        int px = left + MapProjection.pixel(hovered.x(), minWorldX, bpp);
        int py = top + MapProjection.pixel(hovered.z(), minWorldZ, bpp);
        if (hovered.type().temporaryWaypoint()) {
            // 不要框：把白色三角直接压成灰色，像被按住
            MarkerHud.drawTriangle(graphics, px, py, 5, 0xFF8A8A8A);
            return;
        }
        String label = hovered.data().getString(MarkerData.KEY_LABEL);
        boolean nameGlyph = hovered.type().waypoint()
                && MarkerData.showsName(hovered) && !label.isBlank();
        if (nameGlyph) {
            // 名字本身就是位置标志：只在底下垫一层暗背景当悬停反馈
            int half = minecraft.font.width(label) / 2;
            graphics.fill(px - half - 2, py - 6, px + half + 2, py + 6, 0x55000000);
        } else {
            graphics.fill(px - 8, py - 8, px + 8, py + 8, 0x66000000);
            if (!label.isBlank()) {
                graphics.drawCenteredString(minecraft.font, label, px, py - 20, 0xFFFFFFFF);
            }
        }
    }

    private void drawPlayerMarker(GuiGraphics graphics, Minecraft minecraft,
                                  int left, int top, int view,
                                  double minWorldX, double minWorldZ, double bpp) {
        int playerX = left + MapProjection.pixel(minecraft.player.getX(), minWorldX, bpp);
        int playerZ = top + MapProjection.pixel(minecraft.player.getZ(), minWorldZ, bpp);
        if (playerX < left || playerX >= left + view || playerZ < top || playerZ >= top + view) {
            return;
        }
        graphics.fill(playerX - 2, playerZ - 2, playerX + 3, playerZ + 3, 0xFFFF4040);
    }

    private void renderMenu(GuiGraphics graphics, Minecraft minecraft, int mouseX, int mouseY) {
        int width = menuWidth();
        int height = menuHeight();
        graphics.fill(menuX, menuY, menuX + width, menuY + height, 0xF0101010);
        graphics.fill(menuX, menuY, menuX + width, menuY + 1, 0xFF5A5A5A);
        graphics.fill(menuX, menuY + height - 1, menuX + width, menuY + height, 0xFF000000);
        for (int i = 0; i < menuEntries.size(); i++) {
            int rowY = menuY + 2 + i * MENU_ROW;
            boolean hover = inside(mouseX, mouseY, menuX, rowY, width, MENU_ROW);
            if (hover) {
                graphics.fill(menuX + 1, rowY, menuX + width - 1, rowY + MENU_ROW, 0x30FFFFFF);
            }
            graphics.drawString(minecraft.font, menuEntries.get(i).label().getString(),
                    menuX + 8, rowY + 4, hover ? 0xFFFFFFFF : 0xFFCCCCCC, false);
        }
    }

    private int menuWidth() {
        Minecraft minecraft = Minecraft.getInstance();
        int width = 0;
        for (MenuEntry entry : menuEntries) {
            width = Math.max(width, minecraft.font.width(entry.label()));
        }
        return width + 16;
    }

    private int menuHeight() {
        return menuEntries.size() * MENU_ROW + 4;
    }

    private int menuRowAt(double mouseX, double mouseY) {
        if (mouseX < menuX || mouseX >= menuX + menuWidth()) {
            return -1;
        }
        if (mouseY < menuY + 2) {
            return -1;
        }
        int row = (int) ((mouseY - (menuY + 2)) / MENU_ROW);
        return row < menuEntries.size() ? row : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseDown = true;
        if (menuOpen) {
            int row = menuRowAt(mouseX, mouseY);
            MenuEntry entry = row >= 0 ? menuEntries.get(row) : null;
            menuOpen = false;
            if (entry != null) {
                entry.action().run();
            }
            return true;
        }
        for (MapUiButton widget : buttons) {
            if (widget.click(mouseX, mouseY, button)) {
                return true;
            }
        }
        if (layersOpen && button == 0) {
            if (clickLayerPanel(mouseX, mouseY)) {
                return true;
            }
            // 点在面板外：面板先关，这次点击继续交给地图（平移/放标记）
        }
        if (button == 0 && insideView(mouseX, mouseY)) {
            if (placing) {
                placing = false;
                buildButtons();
                createMapMarker(mouseX, mouseY, true);
                return true;
            }
            pressing = true;
            moved = false;
            pressX = mouseX;
            pressY = mouseY;
            pressedMarker = markerAt(mouseX, mouseY);
            // 只是按一下（选中标记 / 准备放置）不打断跟随；真正拖动才由 mouseDragged 取消
            return true;
        }
        if (button == 1 && insideView(mouseX, mouseY)) {
            handleRightClick(mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private MarkerDto markerAt(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return null;
        }
        double bpp = ZOOMS[zoomIndex];
        double minWorldX = MapProjection.origin(centerX, viewSize(), bpp);
        double minWorldZ = MapProjection.origin(centerZ, viewSize(), bpp);
        int left = left();
        int top = top();
        MarkerDto nearest = null;
        double best = 12.0 * 12.0;
        for (MarkerDto marker : MarkerClient.visibleMarkers(level.dimension().location())) {
            int px = left + MapProjection.pixel(marker.x(), minWorldX, bpp);
            int py = top + MapProjection.pixel(marker.z(), minWorldZ, bpp);
            double distance = (px - mouseX) * (px - mouseX) + (py - mouseY) * (py - mouseY);
            if (distance < best) {
                best = distance;
                nearest = marker;
            }
        }
        return nearest;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (pressing) {
            if (Math.abs(mouseX - pressX) > 3 || Math.abs(mouseY - pressY) > 3) {
                moved = true;
            }
            if (moved) {
                double bpp = ZOOMS[zoomIndex];
                // 第一次真正拖动才开始「脱跟随」：单纯点击不该把地图从玩家身上解绑
                followPlayer = false;
                centerX -= dragX * bpp;
                centerZ -= dragY * bpp;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mouseDown = false;
        if (button == 0 && pressing) {
            pressing = false;
            if (!moved && pressedMarker != null) {
                openEditor(pressedMarker);
            }
            pressedMarker = null;
            moved = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void openEditor(MarkerDto marker) {
        if (!MarkerClient.canEdit(marker)) {
            return; // 临时标记 / 别人分享的标记：不可编辑
        }
        Minecraft.getInstance().setScreen(new MarkerEditScreen(marker));
    }

    /** 右键：在点击处打开菜单（点到标记时给出编辑/删除）。 */
    private void handleRightClick(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        double bpp = ZOOMS[zoomIndex];
        double minWorldX = MapProjection.origin(centerX, viewSize(), bpp);
        double minWorldZ = MapProjection.origin(centerZ, viewSize(), bpp);
        menuWorldX = (int) Math.floor(minWorldX + (mouseX - left()) * bpp);
        menuWorldZ = (int) Math.floor(minWorldZ + (mouseY - top()) * bpp);
        MarkerDto marker = markerAt(mouseX, mouseY);
        menuEntries.clear();
        if (marker != null) {
            // 临时标记 / 别人的标记没有编辑和删除：服务端也会拒，菜单里干脆不给
            if (MarkerClient.canEdit(marker)) {
                menuEntries.add(new MenuEntry(Component.translatable(
                        "catoxidesbattlerebuild.map.menu.edit"), () -> openEditor(marker)));
            }
            if (MarkerClient.canModify(marker)) {
                menuEntries.add(new MenuEntry(Component.translatable(
                        "catoxidesbattlerebuild.map.menu.delete"), () -> deleteMarker(marker)));
            }
        } else {
            menuEntries.add(new MenuEntry(Component.translatable(
                    "catoxidesbattlerebuild.map.menu.waypoint"),
                    () -> createMapMarkerAt(menuWorldX, menuWorldZ, true)));
            menuEntries.add(new MenuEntry(Component.translatable(
                    "catoxidesbattlerebuild.map.menu.ping"),
                    () -> createMapMarkerAt(menuWorldX, menuWorldZ, false)));
        }
        if (menuEntries.isEmpty()) {
            menuOpen = false;
            return;
        }
        int width = menuWidth();
        menuX = Math.max(4, Math.min((int) mouseX, this.width - width - 4));
        menuY = Math.max(4, Math.min((int) mouseY,
                this.height - BOTTOM_BAR - menuHeight() - 4));
        layersOpen = false;
        menuOpen = true;
    }

    private void deleteMarker(MarkerDto marker) {
        PacketDistributor.sendToServer(new MarkerRemovePayload(marker.id()));
    }

    private void createMapMarker(double mouseX, double mouseY, boolean permanent) {
        double bpp = ZOOMS[zoomIndex];
        double minWorldX = MapProjection.origin(centerX, viewSize(), bpp);
        double minWorldZ = MapProjection.origin(centerZ, viewSize(), bpp);
        int worldX = (int) Math.floor(minWorldX + (mouseX - left()) * bpp);
        int worldZ = (int) Math.floor(minWorldZ + (mouseY - top()) * bpp);
        createMapMarkerAt(worldX, worldZ, permanent);
    }

    private void createMapMarkerAt(int worldX, int worldZ, boolean permanent) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        String layer = currentLayer(level);
        int surfaceY = MapStore.sampleHeight(level.dimension().location(), layer, worldX, worldZ);
        double y = surfaceY == Integer.MIN_VALUE ? 64.0 : surfaceY;
        ResourceLocation icon = ResourceLocation.withDefaultNamespace("nether_star");
        PacketDistributor.sendToServer(new MarkerCreatePayload(
                permanent ? MarkerType.STICKY.name() : MarkerType.PING.name(),
                icon, worldX + 0.5, y, worldZ + 0.5,
                -1, -1, "map:" + worldX + "," + worldZ, new CompoundTag(), permanent));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        if (scrollY == 0.0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (layersOpen && layerPanelContains(mouseX, mouseY)) {
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                int total = MapMinimap.availableLayers(level).size();
                int max = Math.max(0, total - LAYER_VISIBLE_ROWS);
                layerScroll = Math.max(0, Math.min(max, layerScroll + (scrollY > 0 ? -1 : 1)));
            }
            return true;
        }
        double oldBpp = ZOOMS[zoomIndex];
        int next = Math.max(0, Math.min(ZOOMS.length - 1,
                zoomIndex + (scrollY > 0 ? -1 : 1)));
        if (next == zoomIndex) {
            return true;
        }
        zoomIndex = next;
        if (followPlayer) {
            // 跟随中视图中心就是玩家：缩放不移动视图，鼠标在哪都不影响（否则 syncView 下一帧
            // 会把锚点算出来的中心覆盖掉，等于白算，还让人以为地图/玩家标记在乱飘）
            return true;
        }
        int view = viewSize();
        int left = left();
        int top = top();
        double relX = mouseX - (left + view / 2.0);
        double relZ = mouseY - (top + view / 2.0);
        double worldX = centerX + relX * oldBpp;
        double worldZ = centerZ + relZ * oldBpp;
        double newBpp = ZOOMS[zoomIndex];
        centerX = worldX - relX * newBpp;
        centerZ = worldZ - relZ * newBpp;
        return true;
    }

    private void zoomIn() {
        zoomIndex = Math.max(0, zoomIndex - 1);
    }

    private void zoomOut() {
        zoomIndex = Math.min(ZOOMS.length - 1, zoomIndex + 1);
    }

    private void recenter() {
        followPlayer = true;
    }

    private void togglePlacing() {
        placing = !placing;
        buildButtons();
    }

    private void hint(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal(message), true);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && (menuOpen || layersOpen)) {
            menuOpen = false;
            layersOpen = false;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_C) {
            followPlayer = true;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            cycleLayer(keyCode == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void syncView() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        if (followPlayer) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
        String layer = currentLayer(level);
        ResourceLocation dimension = level.dimension().location();
        if (!dimension.equals(lastDimension) || !layer.equals(lastLayer)) {
            // 纹理缓存键里已经带了维度/层，切换不需要清空
            lastDimension = dimension;
            lastLayer = layer;
        }
    }

    private void cycleLayer(int delta) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        List<String> layers = MapMinimap.availableLayers(level);
        int index = layers.indexOf(currentLayer(level));
        if (index < 0) {
            index = 0;
        }
        MapMinimap.setLayerOverride(layers.get(Math.floorMod(index + delta, layers.size())));
        lastLoadRequestNanos = 0L; // 换层立刻重发可见瓦片请求，别等 250ms 节流
        syncView();
    }

    private String currentLayer(ClientLevel level) {
        return MapMinimap.activeLayer(level);
    }

    private void toggleLayers() {
        layersOpen = !layersOpen;
        layerScroll = 0;
        menuOpen = false;
        if (layersOpen) {
            snapshotLayerCounts();
        }
    }

    /** 打开面板时数一次每层瓦片数，好在列表里区分「有数据」和「刚空出来」。 */
    private void snapshotLayerCounts() {
        layerCounts.clear();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ResourceLocation dimension = level.dimension().location();
        for (String layer : MapMinimap.availableLayers(level)) {
            layerCounts.put(layer, MapStorage.tileCount(dimension, layer));
        }
    }

    private void renderLayerPanel(GuiGraphics graphics, ClientLevel level, int mouseX, int mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        List<String> layers = MapMinimap.availableLayers(level);
        int shown = Math.min(LAYER_VISIBLE_ROWS, layers.size());
        int scroll = layerScrollFor(layers);
        int x = layerPanelX();
        int y = layerPanelY(layers.size());
        int height = layerPanelHeight(layers.size());
        graphics.fill(x, y, x + LAYER_PANEL_WIDTH, y + height, 0xF0101010);
        graphics.fill(x, y, x + LAYER_PANEL_WIDTH, y + 1, 0xFF4A4A4A);
        graphics.drawString(minecraft.font, "Layers", x + 6, y + 6, 0xFFFFFFFF, true);
        if (layers.size() > LAYER_VISIBLE_ROWS) {
            String range = (scroll + 1) + "-" + (scroll + shown) + "/" + layers.size();
            graphics.drawString(minecraft.font, range,
                    x + LAYER_PANEL_WIDTH - 6 - minecraft.font.width(range), y + 6,
                    0xFF8A8A8A, false);
        }

        String current = currentLayer(level);
        for (int i = 0; i < shown; i++) {
            String layer = layers.get(scroll + i);
            int rowY = y + LAYER_PANEL_PAD + i * LAYER_ROW_HEIGHT;
            boolean active = layer.equals(current);
            boolean hover = inside(mouseX, mouseY, x, rowY, LAYER_PANEL_WIDTH, LAYER_ROW_HEIGHT);
            if (active || hover) {
                graphics.fill(x + 1, rowY, x + LAYER_PANEL_WIDTH - 1, rowY + LAYER_ROW_HEIGHT,
                        active ? 0x6040A0FF : 0x28FFFFFF);
            }
            graphics.drawString(minecraft.font, (active ? "> " : "  ") + MapLayer.label(layer),
                    x + 6, rowY + 5, active ? 0xFFFFFFFF : 0xFFCCCCCC, false);
            int tiles = layerCounts.getOrDefault(layer, 0);
            String meta = tiles > 0 ? tiles + " tiles" : "no tiles";
            graphics.drawString(minecraft.font, meta,
                    x + LAYER_PANEL_WIDTH - 6 - minecraft.font.width(meta), rowY + 5,
                    tiles > 0 ? 0xFF8A8A8A : 0xFFE08080, false);
        }
        graphics.drawString(minecraft.font, "biome layer: surface only",
                x + 6, y + LAYER_PANEL_PAD + shown * LAYER_ROW_HEIGHT + 4, 0xFF7A7A7A, false);
    }

    /** 返回 true = 这次点击被面板吃掉；false = 点在面板外，已关闭面板。 */
    private boolean clickLayerPanel(double mouseX, double mouseY) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            layersOpen = false;
            return true;
        }
        List<String> layers = MapMinimap.availableLayers(level);
        int y = layerPanelY(layers.size());
        if (!inside(mouseX, mouseY, layerPanelX(), y,
                LAYER_PANEL_WIDTH, layerPanelHeight(layers.size()))) {
            layersOpen = false;
            return false;
        }
        int shown = Math.min(LAYER_VISIBLE_ROWS, layers.size());
        int row = (int) ((mouseY - (y + LAYER_PANEL_PAD)) / LAYER_ROW_HEIGHT);
        if (mouseY >= y + LAYER_PANEL_PAD && row >= 0 && row < shown) {
            MapMinimap.setLayerOverride(layers.get(layerScrollFor(layers) + row));
            layersOpen = false;
            lastLoadRequestNanos = 0L;
            syncView();
        }
        return true;
    }

    private boolean layerPanelContains(double mouseX, double mouseY) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        int count = MapMinimap.availableLayers(level).size();
        return inside(mouseX, mouseY, layerPanelX(), layerPanelY(count),
                LAYER_PANEL_WIDTH, layerPanelHeight(count));
    }

    /** 列表变短（换维度/世界）时把滚动位置夹回合法范围。 */
    private int layerScrollFor(List<String> layers) {
        int visible = Math.min(LAYER_VISIBLE_ROWS, layers.size());
        return Math.max(0, Math.min(layerScroll, Math.max(0, layers.size() - visible)));
    }

    private int layerPanelHeight(int layerCount) {
        return LAYER_PANEL_PAD + Math.min(LAYER_VISIBLE_ROWS, layerCount) * LAYER_ROW_HEIGHT + 16;
    }

    private int layerPanelX() {
        return 10;
    }

    private int layerPanelY(int layerCount) {
        return this.height - BOTTOM_BAR - 6 - layerPanelHeight(layerCount);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y,
                                  int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private boolean insideView(double mouseX, double mouseY) {
        int view = viewSize();
        int left = left();
        int top = top();
        return mouseX >= left && mouseX < left + view && mouseY >= top && mouseY < top + view;
    }

    private int viewSize() {
        return Math.min(this.width, this.height - BOTTOM_BAR - 16) - MARGIN;
    }

    private int left() {
        return (this.width - viewSize()) / 2;
    }

    private int top() {
        return (this.height - BOTTOM_BAR - viewSize()) / 2;
    }

    private static void blitTile(GuiGraphics graphics, ResourceLocation texture,
                                 int tileX, int tileZ, int left, int top,
                                 double minWorldX, double minWorldZ, double bpp,
                                 int screenTile, int lodSize) {
        int screenX = left + MapProjection.pixel(tileX * MapTile.SIZE, minWorldX, bpp);
        int screenZ = top + MapProjection.pixel(tileZ * MapTile.SIZE, minWorldZ, bpp);
        // 放大档 screenTile > lodSize，目标尺寸和源区域必须分开给。
        // 9 参 blit 会把目标尺寸当成源区域（UV = 0..screenTile/lodSize），
        // 贴图不是被放大，而是在一块里平铺 N 次。
        graphics.blit(texture, screenX, screenZ, screenTile, screenTile,
                0.0f, 0.0f, lodSize, lodSize, lodSize, lodSize);
    }

    /**
     * 某个 bpp 用哪一级 LOD。{@code bpp < 1} 是放大（方块占多个像素），永远用最细的 0 级。
     */
    private static int lodFor(double bpp) {
        if (bpp <= 1.0) {
            return 0;
        }
        int level = Integer.numberOfTrailingZeros(Integer.highestOneBit((int) Math.round(bpp)));
        return Math.min(TileLodTextures.MAX_LOD, level);
    }

    private static int nearestZoomIndex(double bpp) {
        int best = 0;
        for (int i = 0; i < ZOOMS.length; i++) {
            if (ZOOMS[i] <= bpp) {
                best = i;
            }
        }
        return best;
    }
}
