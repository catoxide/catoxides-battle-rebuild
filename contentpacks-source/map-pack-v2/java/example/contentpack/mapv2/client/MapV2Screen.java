package example.contentpack.mapv2.client;

import com.catoxide.catoxidesbattlerebuild.core.map.MapRenderContext;
import example.contentpack.mapv2.MapConfig;
import example.contentpack.mapv2.MapHooks;
import example.contentpack.mapv2.MapLayer;
import example.contentpack.mapv2.MapLayerSelection;
import example.contentpack.mapv2.MapLayerStack;
import example.contentpack.mapv2.MapStorage;
import example.contentpack.mapv2.MapStore;
import example.contentpack.mapv2.MapTile;
import example.contentpack.mapv2.marker.MarkerData;
import example.contentpack.mapv2.marker.MarkerDto;
import example.contentpack.mapv2.marker.MarkerType;
import example.contentpack.mapv2.network.MarkerCreatePayload;
import example.contentpack.mapv2.network.MarkerRemovePayload;
import example.contentpack.mapv2.structure.StructureEntry;
import example.contentpack.mapv2.view.MapProjection;
import example.contentpack.mapv2.view.MapZoom;
import example.contentpack.mapv2.view.UiScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 全屏世界地图：画布按<b>屏幕像素</b>绘制，外层 UI 仍是 GUI 像素。
 *
 * <p>画布坐标 = 帧缓冲像素：{@code pose().scale(1/guiScale)} 之后按画布坐标绘制，
 * 于是 1 画布像素就是显示器 1 像素，GUI 缩放只影响文字/面板大小，不影响地图清晰度。
 * 裁剪框（scissor）不走 pose，仍然传 GUI 坐标。
 *
 * <p>视口是<b>屏幕比例</b>的矩形（横向/纵向各自独立算原点），不再强制正方。
 * 放大档（{@code bpp <= 1}）视图原点吸附到像素格点；LOD 由 {@link MapZoom} 决定，
 * 档位为 2 的幂时一个 texel 恰一个画布像素，纹理只做整数倍最近邻放大。
 */
public final class MapV2Screen extends Screen {

    private static final double DEFAULT_BPP = 2.0;
    private static final int MARGIN_GUI = 10;
    private static final int BOTTOM_BAR_GUI = 30;
    private static final int PANEL_ROW = 11;
    private static final int PANEL_WIDTH = 132;
    private static final int PANEL_X = 6;
    private static final int PANEL_TOP_OFFSET = 34;
    private static final long MAX_TILES_FOR_LOADS = 4096;
    private static final int MENU_ROW = 16;

    private static final int BUTTON_WIDTH = 90;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SIDE_BUTTON = 22;

    private final TileLodTextures textures = TileLodTextures.shared();
    private final List<MapUiButton> buttons = new ArrayList<>();
    private final List<MenuEntry> menuEntries = new ArrayList<>();

    private double centerX;
    private double centerZ;
    private double bpp = DEFAULT_BPP;
    private boolean follow = true;
    private boolean dragging;
    private double lastDragCanvasX;
    private double lastDragCanvasY;
    private boolean layerPanelOpen;
    private int layerScroll;

    private String layer = MapLayer.TOP;
    private List<String> layers = List.of();

    private int tileMemory;
    private int tileDisk;
    private int tileLoading;
    private int tileMissing;
    private int coverage;

    private boolean placing;
    private boolean mouseDown;
    private MarkerDto pressedMarker;
    private boolean moved;

    private boolean menuOpen;
    private int menuX;
    private int menuY;
    private int menuWorldX;
    private int menuWorldZ;

    private record MenuEntry(Component label, Runnable action) {
    }

    public MapV2Screen() {
        super(Component.translatable("catoxidesbattlerebuild.map.title"));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
        String selected = MapLayerSelection.selected();
        if (selected != null) {
            layer = selected;
        }
    }

    /**
     * 画布矩形（帧缓冲像素）。左右/上下边距一致，视口比例 = 屏幕比例减去底部工具栏。
     *
     * <p>两套缩放同时存在：{@code guiScale} 是系统的（画布 = 帧缓冲像素，贴图清晰度靠它），
     * {@code uiScale} 是地图界面自己的（边距、按钮、文字）。{@code uiWidth/uiHeight}
     * 是界面坐标空间的大小，绘制时整体乘 {@link #chromeScale()}。
     */
    private record Layout(int guiScale, int uiScale, int viewWidth, int viewHeight,
                          int left, int top, int uiWidth, int uiHeight) {

        float chromeScale() {
            return this.uiScale / (float) this.guiScale;
        }

        /** 系统鼠标坐标 → 界面坐标（与 {@link #chromeScale()} 互逆）。 */
        double toUiX(double mouseX) {
            return mouseX * this.guiScale / this.uiScale;
        }

        double toUiY(double mouseY) {
            return mouseY * this.guiScale / this.uiScale;
        }
    }

    private static Layout layout(Minecraft minecraft, int width, int height) {
        int guiScale = Math.max(1, (int) minecraft.getWindow().getGuiScale());
        int uiScale = UiScale.resolve(MapConfig.uiScale(), guiScale);
        int canvasWidth = width * guiScale;
        int canvasHeight = height * guiScale;
        int margin = MARGIN_GUI * uiScale;
        int bar = BOTTOM_BAR_GUI * uiScale;
        int viewWidth = Math.max(64, canvasWidth - 2 * margin);
        int viewHeight = Math.max(64, canvasHeight - 2 * margin - bar);
        return new Layout(guiScale, uiScale, viewWidth, viewHeight, margin, margin,
                canvasWidth / uiScale, canvasHeight / uiScale);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        buildButtons();
    }

    private void buildButtons() {
        buttons.clear();
        Layout layout = layout(Minecraft.getInstance(), this.width, this.height);
        int barY = layout.uiHeight() - BOTTOM_BAR_GUI + 4;
        // 底部按钮整体居中：宽度 = 90 + 6 + 90 + 6 + 80 + 6 + 80
        int bx = (layout.uiWidth() - (BUTTON_WIDTH * 2 + 80 * 2 + 18)) / 2;
        buttons.add(new MapUiButton(bx, barY, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("catoxidesbattlerebuild.map.ui.button.markers"),
                () -> Minecraft.getInstance().setScreen(new MarkerListScreen())));
        bx += BUTTON_WIDTH + 6;
        buttons.add(new MapUiButton(bx, barY, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable(placing
                        ? "catoxidesbattlerebuild.map.ui.button.placing"
                        : "catoxidesbattlerebuild.map.ui.button.place"),
                this::togglePlacing));
        bx += BUTTON_WIDTH + 6;
        buttons.add(new MapUiButton(bx, barY, 80, BUTTON_HEIGHT,
                Component.translatable("catoxidesbattlerebuild.map.ui.button.layers"),
                this::toggleLayers));
        bx += 80 + 6;
        buttons.add(new MapUiButton(bx, barY, 80, BUTTON_HEIGHT,
                Component.translatable(StructureClient.isVisible()
                        ? "catoxidesbattlerebuild.map.ui.button.structures"
                        : "catoxidesbattlerebuild.map.ui.button.structures_off"),
                this::toggleStructures));

        int sideX = layout.uiWidth() - SIDE_BUTTON - 6;
        int sideY = MARGIN_GUI;
        buttons.add(new MapUiButton(sideX, sideY, SIDE_BUTTON, SIDE_BUTTON,
                Component.literal("+"), this::zoomIn));
        buttons.add(new MapUiButton(sideX, sideY + 26, SIDE_BUTTON, SIDE_BUTTON,
                Component.literal("-"), this::zoomOut));
        buttons.add(new MapUiButton(sideX, sideY + 52, SIDE_BUTTON, SIDE_BUTTON,
                Component.literal("C"), this::recenter));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        // 上一帧的 blit 已提交完，这时回收淘汰的纹理才不会让本帧指向已注销的贴图
        TileLodTextures.beginFrame();
        ResourceLocation dimension = level.dimension().location();
        Layout layout = layout(minecraft, this.width, this.height);
        int guiScale = layout.guiScale();
        int viewWidth = layout.viewWidth();
        int viewHeight = layout.viewHeight();
        int left = layout.left();
        int top = layout.top();

        refreshLayers(level, dimension);
        if (follow && minecraft.player != null) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
        double originX = MapProjection.origin(centerX, viewWidth, bpp);
        double originZ = MapProjection.origin(centerZ, viewHeight, bpp);
        int minTileX = Math.floorDiv((int) Math.floor(originX), MapTile.SIZE);
        int minTileZ = Math.floorDiv((int) Math.floor(originZ), MapTile.SIZE);
        int maxTileX = Math.floorDiv((int) Math.floor(originX + viewWidth * bpp), MapTile.SIZE);
        int maxTileZ = Math.floorDiv((int) Math.floor(originZ + viewHeight * bpp), MapTile.SIZE);

        int minY = level.getMinBuildHeight();
        int heightRange = Math.max(1, level.getMaxBuildHeight() - minY - 1);
        int lod = MapZoom.lod(bpp);
        int lodSize = MapZoom.lodTextureSize(lod);
        boolean prediction = MapLayer.showsBiome(layer, level.dimension() == Level.NETHER)
                && MapConfig.showBiomePrediction() && !layerPanelOpen;
        if (prediction) {
            MapBiomeLayer.ensureVisible(dimension,
                    (int) Math.floor(originX), (int) Math.floor(originZ),
                    (int) Math.ceil(Math.max(viewWidth, viewHeight) * bpp) + 256);
        }

        int scaleBlocks = scaleBarBlocks();
        int scalePixels = (int) Math.round(scaleBlocks / bpp);
        int barRight = left + viewWidth - 8;
        int barLeft = barRight - scalePixels;
        int barY = top + viewHeight - 12;

        graphics.fill(0, 0, this.width, this.height, 0xD0101010);

        graphics.pose().pushPose();
        graphics.pose().scale(1f / guiScale, 1f / guiScale, 1f);
        graphics.fill(left - 1, top - 1, left + viewWidth + 1, top + viewHeight + 1, 0xFF000000);
        graphics.enableScissor(left / guiScale, top / guiScale,
                (left + viewWidth + guiScale - 1) / guiScale,
                (top + viewHeight + guiScale - 1) / guiScale);

        // 切片留空像素 ⇒ 从低到高把选中层及以下有数据的切片叠起来，空处露出下层
        List<String> stack = MapLayerStack.build(layers, layer);
        List<String> sampleOrder = stack.reversed();
        // 探明范围一变，预测纹理的可见像素就变了，缓存必须跟着失效
        long biomeVersion = prediction
                ? MapBiomeLayer.version() + MapRevealClient.version() : 0L;

        // 第一遍：群系预测——按视口矩形整片铺，没采过的分区也要有底
        if (prediction) {
            boolean outline = MapConfig.predictionOutline();
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                    ResourceLocation texture = textures.prediction(dimension, layer,
                            tileX, tileZ, lod, biomeVersion, outline);
                    if (texture != null) {
                        blitTile(graphics, texture, tileX, tileZ, left, top,
                                originX, originZ, lodSize);
                    }
                }
            }
        }
        // 第二遍：真实地形——整条栈合成一张瓦片纹理（空像素露出下层），每块瓦片只 blit 一次
        int sharpen = MapConfig.zoomSharpen();
        int relief = MapConfig.terrainRelief();
        for (long coord : stackedTileCoords(dimension, stack,
                minTileX, maxTileX, minTileZ, maxTileZ)) {
            int tileX = (int) (coord >> 32);
            int tileZ = (int) coord;
            blitTile(graphics, textures.get(dimension, stack, tileX, tileZ,
                            minY, heightRange, lod, sharpen, relief),
                    tileX, tileZ, left, top, originX, originZ, lodSize);
        }
        // 第三遍：放大到每方块至少 16 像素时，换成各方块自己的顶面贴图（同样按叠层采样）
        BlockTopTextures.render(graphics, left, top, Math.max(viewWidth, viewHeight),
                dimension, sampleOrder, originX, originZ, bpp, minY, heightRange);
        // 第三点二五遍：扩展叠加层（sable 船体俯视快照）——画布坐标，与地形同一视口
        if (MapHooks.INSTANCE.hasOverlays()) {
            MapHooks.INSTANCE.renderOverlays(new MapRenderContext(graphics, true,
                    left, top, viewWidth, viewHeight, bpp,
                    originX, originZ, dimension, partialTick));
        }
        // 第三点五遍：结构候选点（独立叠加层，画在标记下面）
        StructureOverlay.render(graphics, minecraft, left, top, viewWidth, viewHeight,
                bpp, originX, originZ, dimension);
        // 第四遍：标记（在画布内绘制，随地图一起裁剪/缩放）
        MarkerHud.renderFullmap(graphics, minecraft, left, top, viewWidth, viewHeight, bpp,
                originX, originZ, dimension, partialTick);
        drawPlayerMarker(graphics, minecraft, left, top, originX, originZ);
        highlightHoveredMarker(graphics, minecraft, mouseX, mouseY, layout, originX, originZ);
        drawScaleBarFrame(graphics, barLeft, barY, barRight);

        graphics.disableScissor();
        graphics.pose().popPose();

        countTiles(dimension, minTileX, maxTileX, minTileZ, maxTileZ);
        for (String stackLayer : stack) {
            requestVisibleLoads(dimension, stackLayer, minTileX, maxTileX, minTileZ, maxTileZ);
        }

        // 界面层（文字/面板/按钮）：独立于系统 GUI 缩放的坐标空间
        double uiMouseX = layout.toUiX(mouseX);
        double uiMouseY = layout.toUiY(mouseY);
        int uiWidth = layout.uiWidth();
        int uiHeight = layout.uiHeight();
        graphics.pose().pushPose();
        graphics.pose().scale(layout.chromeScale(), layout.chromeScale(), 1f);

        drawStatus(graphics, minecraft, level, dimension, mouseX, mouseY, layout, originX, originZ);

        String scaleLabel = scaleBlocks + " b";
        graphics.drawString(minecraft.font, scaleLabel,
                (barLeft + barRight) / 2 / layout.uiScale() - minecraft.font.width(scaleLabel) / 2,
                barY / layout.uiScale() - 10, 0xFFFFFFFF, false);

        // 底部工具栏 + 右侧缩放按钮
        graphics.fill(0, uiHeight - BOTTOM_BAR_GUI, uiWidth, uiHeight, 0xC0101010);
        for (MapUiButton button : buttons) {
            button.render(graphics, minecraft.font, (int) uiMouseX, (int) uiMouseY, mouseDown);
        }
        if (placing) {
            graphics.drawCenteredString(minecraft.font,
                    Component.translatable("catoxidesbattlerebuild.map.ui.place_hint"),
                    uiWidth / 2, MARGIN_GUI - 2, 0xFFFFCC44);
        }
        if (layerPanelOpen) {
            drawLayerPanel(graphics, minecraft, dimension, layout);
        }
        if (menuOpen) {
            renderMenu(graphics, minecraft, (int) uiMouseX, (int) uiMouseY);
        }
        drawHoveredTooltip(graphics, minecraft, mouseX, mouseY, layout, originX, originZ);
        graphics.pose().popPose();
    }

    /** 拉伸到画布上的瓦片：目标尺寸与源区域必须分开给，9 参 blit 会平铺（旧包的实崩教训）。 */
    private void blitTile(GuiGraphics graphics, ResourceLocation texture,
                          int tileX, int tileZ, int left, int top,
                          double originX, double originZ, int lodSize) {
        int size = MapZoom.tilePixels(bpp);
        int x = left + MapProjection.pixel(tileX * MapTile.SIZE, originX, bpp);
        int z = top + MapProjection.pixel(tileZ * MapTile.SIZE, originZ, bpp);
        graphics.blit(texture, x, z, size, size, 0.0f, 0.0f, lodSize, lodSize, lodSize, lodSize);
    }

    /** 视口内任一叠层有数据的瓦片坐标（瓦片坐标打包成 long，避免同一块被合成两次）。 */
    private static Set<Long> stackedTileCoords(ResourceLocation dimension, List<String> stack,
                                               int minTileX, int maxTileX,
                                               int minTileZ, int maxTileZ) {
        Set<Long> coords = new LinkedHashSet<>();
        for (String stackLayer : stack) {
            for (MapStore.TileAt at : MapStore.visibleTiles(dimension, stackLayer,
                    minTileX, maxTileX, minTileZ, maxTileZ)) {
                coords.add(((long) at.x() << 32) ^ (at.z() & 0xFFFFFFFFL));
            }
        }
        return coords;
    }

    private void drawPlayerMarker(GuiGraphics graphics, Minecraft minecraft,
                                  int left, int top, double originX, double originZ) {
        if (minecraft.player == null) {
            return;
        }
        int x = left + MapProjection.pixel(minecraft.player.getX(), originX, bpp);
        int z = top + MapProjection.pixel(minecraft.player.getZ(), originZ, bpp);
        // 头像是 GUI 尺寸：画布坐标里要乘 guiScale
        int size = Math.max(6, (int) Math.round(12 * minecraft.getWindow().getGuiScale()));
        MarkerHud.drawPlayerHead(graphics, minecraft, x, z, size);
    }

    /**
     * 视口内每块瓦片到哪一步了：内存 / 磁盘待读 / 读盘中 / 磁盘也没有。
     * 用来区分「没采过」和「采了但没读回来」。
     */
    private void countTiles(ResourceLocation dimension,
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
        tileMemory = memory;
        tileDisk = disk;
        tileLoading = loading;
        tileMissing = missing;
        coverage = totalPixels == 0 ? 0 : (int) (knownPixels * 100 / totalPixels);
    }

    /** 磁盘上有、内存里没有的可见瓦片排进读队列；视口大到离谱时整帧跳过，别塞爆 IO 队列。 */
    private void requestVisibleLoads(ResourceLocation dimension, String targetLayer,
                                     int minTileX, int maxTileX, int minTileZ, int maxTileZ) {
        long tileCount = (long) (maxTileX - minTileX + 1) * (maxTileZ - minTileZ + 1);
        if (!MapStorage.indexReady() || tileCount > MAX_TILES_FOR_LOADS) {
            return;
        }
        for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                if (MapStorage.status(dimension, targetLayer, tileX, tileZ)
                        == MapStorage.TileStatus.DISK) {
                    MapStorage.requestLoad(dimension, targetLayer, tileX, tileZ);
                }
            }
        }
    }

    /**
     * 可选的图层：surface + 磁盘索引/内存里有瓦片的切片 + 玩家所在切片。
     * 按 {@link MapLayer#ORDER} 排序，超出维度高度的旧层名不列。
     */
    private void refreshLayers(ClientLevel level, ResourceLocation dimension) {
        layer = MapMinimap.activeLayer(level);
        Set<String> names = new LinkedHashSet<>();
        names.add(MapLayer.TOP);
        names.addAll(MapStore.layers(dimension));
        names.addAll(MapStorage.knownLayers(dimension));
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) {
            names.add(layer);
        } else {
            names.add(MapLayer.section(MapLayer.sectionStart(level.getMinBuildHeight(),
                    level.getMinBuildHeight())));
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            names.add(MapLayer.section(minecraft.player.blockPosition().getY()));
        }
        List<String> result = new ArrayList<>();
        for (String name : names) {
            if (MapLayer.TOP.equals(name)
                    || MapLayer.inRange(name, level.getMinBuildHeight(), level.getMaxBuildHeight())) {
                result.add(name);
            }
        }
        result.sort(MapLayer.ORDER);
        layers = result;
        if (!layers.contains(layer)) {
            layer = MapLayer.TOP;
        }
        layerScroll = Math.max(0, Math.min(layerScroll, Math.max(0, layers.size() - panelRows())));
    }

    private int panelRows() {
        Layout layout = layout(Minecraft.getInstance(), this.width, this.height);
        int maxHeight = layout.uiHeight() - (PANEL_TOP_OFFSET + 20);
        return Math.max(3, maxHeight / PANEL_ROW);
    }

    private int panelTop(Minecraft minecraft) {
        Layout layout = layout(minecraft, this.width, this.height);
        return layout.top() / layout.uiScale() + PANEL_TOP_OFFSET;
    }

    /**
     * 状态信息条：底部居中、半透明黑底压在地图上（原来在左上角，会和地图内容抢位置）。
     * 悬停坐标行只在鼠标位于视口内时出现。
     */
    private void drawStatus(GuiGraphics graphics, Minecraft minecraft, ClientLevel level,
                            ResourceLocation dimension, int mouseX, int mouseY,
                            Layout layout, double originX, double originZ) {
        List<String> lines = new ArrayList<>();
        // 调试读数（mem/disk/...）保持英文；这里只译字段名与状态文案（D79）
        lines.add(Component.translatable("catoxidesbattlerebuild.map.ui.layer").getString()
                + " " + MapLayer.label(layer) + "   " + zoomLabel()
                + (follow ? "   " + Component.translatable(
                        "catoxidesbattlerebuild.map.ui.follow").getString() : ""));
        lines.add("mem " + tileMemory + "  disk " + tileDisk + "  load " + tileLoading
                + "  miss " + tileMissing + "  cov " + coverage + "%"
                + (MapStorage.indexReady() ? "" : "  (index building)"));
        if (MapWorld.id() == null) {
            lines.add(Component.translatable("catoxidesbattlerebuild.map.waiting_world").getString());
        } else {
            int mouseCanvasX = mouseX * layout.guiScale();
            int mouseCanvasY = mouseY * layout.guiScale();
            if (mouseCanvasX >= layout.left() && mouseCanvasX < layout.left() + layout.viewWidth()
                    && mouseCanvasY >= layout.top()
                    && mouseCanvasY < layout.top() + layout.viewHeight()) {
                int blockX = (int) Math.floor(
                        MapProjection.world(mouseCanvasX - layout.left(), originX, bpp));
                int blockZ = (int) Math.floor(
                        MapProjection.world(mouseCanvasY - layout.top(), originZ, bpp));
                StringBuilder text = new StringBuilder();
                text.append(blockX).append(", ").append(blockZ);
                MapStore.StackSample sample = MapStore.sampleStack(dimension,
                        MapLayerStack.sampleOrder(layers, layer), blockX, blockZ);
                int mapHeight = sample.height();
                if (MapConfig.showBlockInfo()) {
                    if (sample.known()) {
                        text.append("  y=").append(mapHeight);
                    }
                    String block = MapProbe.blockName(sample.block());
                    if (block != null) {
                        text.append("  ").append(block);
                    }
                }
                // 已加载区块用世界真实群系（已确认）；只有未加载区域才用预测（前面缀 ~）
                int biomeY = mapHeight != Integer.MIN_VALUE ? mapHeight
                        : level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ) - 1;
                String actual = MapProbe.loadedBiomeName(level, blockX, biomeY, blockZ);
                if (actual != null) {
                    text.append("  ").append(actual);
                } else if (MapLayer.showsBiome(layer, level.dimension() == Level.NETHER)) {
                    String predicted = MapBiomeLayer.biomeNameAt(dimension, blockX, blockZ);
                    if (predicted != null) {
                        text.append("  ~").append(predicted);
                    }
                }
                lines.add(text.toString());
            } else {
                // 鼠标不在视口上：给一行通用操作提示（与 v1 地图底部提示同一套键）
                lines.add(Component.translatable("catoxidesbattlerebuild.map.hint").getString());
            }
        }

        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, minecraft.font.width(line));
        }
        int panelWidth = textWidth + 16;
        int panelHeight = lines.size() * 10 + 8;
        int x0 = (layout.uiWidth() - panelWidth) / 2;
        int y0 = layout.uiHeight() - BOTTOM_BAR_GUI - panelHeight - 6;
        graphics.fill(x0, y0, x0 + panelWidth, y0 + panelHeight, 0x99000000);
        int y = y0 + 4;
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? 0xFFFFFFFF : 0xFFE0E0E0;
            graphics.drawString(minecraft.font, lines.get(i), x0 + 8, y, color, true);
            y += 10;
        }
    }

    private String zoomLabel() {
        if (bpp >= 1.0) {
            return "1px=" + trim(bpp) + "b";
        }
        return "1b=" + Math.round(1.0 / bpp) + "px";
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value)
                : String.format(Locale.ROOT, "%.2f", value);
    }

    /** 比例尺长度：让尺子落在 120~600 画布像素（GUI 缩放下约 40~200）。 */
    private int scaleBarBlocks() {
        int[] candidates = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000};
        int chosen = candidates[candidates.length - 1];
        for (int candidate : candidates) {
            chosen = candidate;
            if (candidate / bpp >= 120) {
                break;
            }
        }
        return chosen;
    }

    private void drawScaleBarFrame(GuiGraphics graphics, int x0, int y, int x1) {
        graphics.fill(x0, y, x1, y + 1, 0xFFFFFFFF);
        graphics.fill(x0, y - 3, x0 + 1, y + 1, 0xFFFFFFFF);
        graphics.fill(x1 - 1, y - 3, x1, y + 1, 0xFFFFFFFF);
    }

    private void drawLayerPanel(GuiGraphics graphics, Minecraft minecraft,
                                ResourceLocation dimension, Layout layout) {
        int x = PANEL_X;
        int y = panelTop(minecraft);
        int rows = Math.min(panelRows(), layers.size());
        int height = rows * PANEL_ROW + 4;
        graphics.fill(x, y, x + PANEL_WIDTH, y + height, 0xF0101010);
        for (int i = 0; i < rows; i++) {
            String name = layers.get(layerScroll + i);
            boolean current = name.equals(layer);
            int rowY = y + 2 + i * PANEL_ROW;
            if (current) {
                graphics.fill(x + 1, rowY, x + PANEL_WIDTH - 1, rowY + PANEL_ROW, 0x30FFFFFF);
            }
            int count = MapStorage.tileCount(dimension, name);
            String label = (current ? "> " : "  ") + MapLayer.label(name);
            graphics.drawString(minecraft.font, label, x + 4, rowY + 1,
                    current ? 0xFFFFFFFF : 0xFFCCCCCC, false);
            String tiles = Integer.toString(count);
            graphics.drawString(minecraft.font, tiles,
                    x + PANEL_WIDTH - 4 - minecraft.font.width(tiles), rowY + 1,
                    count == 0 ? 0xFFFF6666 : 0xFF999999, false);
        }
        graphics.drawString(minecraft.font,
                Component.translatable("catoxidesbattlerebuild.map.ui.layer_hint").getString(),
                x + 4, y + height + 2, 0xFF888888, false);
    }

    // ---------------------------------------------------------------- markers

    private void highlightHoveredMarker(GuiGraphics graphics, Minecraft minecraft,
                                        int mouseX, int mouseY, Layout layout,
                                        double originX, double originZ) {
        if (!insideView(mouseX, mouseY, layout)) {
            return;
        }
        MarkerDto hovered = markerAt(mouseX, mouseY, layout, originX, originZ);
        if (hovered == null) {
            return;
        }
        Vec3 world = markerWorld(minecraft.level, hovered);
        int px = layout.left() + MapProjection.pixel(world.x, originX, bpp);
        int py = layout.top() + MapProjection.pixel(world.z, originZ, bpp);
        float scale = layout.guiScale();
        if (hovered.type().temporaryWaypoint()) {
            // 不要框：把白色三角直接压成灰色，像被按住
            graphics.pose().pushPose();
            graphics.pose().translate(px, py, 0f);
            graphics.pose().scale(scale, scale, 1f);
            MarkerHud.drawTriangle(graphics, 0, 0, 5, 0xFF8A8A8A);
            graphics.pose().popPose();
            return;
        }
        String label = hovered.data().getString(MarkerData.KEY_LABEL);
        boolean nameGlyph = hovered.type().waypoint()
                && MarkerData.showsName(hovered) && !label.isBlank();
        int half = (int) Math.round(8 * scale);
        if (nameGlyph) {
            // 名字本身就是位置标志：只在底下垫一层暗背景当悬停反馈
            int textHalf = minecraft.font.width(label) * layout.guiScale() / 2;
            graphics.fill(px - textHalf - 2, py - 6 * layout.guiScale(),
                    px + textHalf + 2, py + 6 * layout.guiScale(), 0x55000000);
        } else {
            graphics.fill(px - half, py - half, px + half, py + half, 0x66000000);
        }
    }

    private void drawHoveredTooltip(GuiGraphics graphics, Minecraft minecraft,
                                    int mouseX, int mouseY, Layout layout,
                                    double originX, double originZ) {
        if (menuOpen || placing || !insideView(mouseX, mouseY, layout)) {
            return;
        }
        MarkerDto hovered = markerAt(mouseX, mouseY, layout, originX, originZ);
        if (hovered != null) {
            MarkerHud.drawTooltip(graphics, minecraft, hovered,
                    (int) layout.toUiX(mouseX), (int) layout.toUiY(mouseY));
            return;
        }
        StructureEntry structure = structureAt(mouseX, mouseY, layout, originX, originZ);
        if (structure != null) {
            StructureOverlay.drawTooltip(graphics, minecraft, structure,
                    (int) layout.toUiX(mouseX), (int) layout.toUiY(mouseY));
        }
    }

    private StructureEntry structureAt(double mouseX, double mouseY, Layout layout,
                                       double originX, double originZ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        return StructureClient.entryAt(
                (int) Math.round(mouseX * layout.guiScale()),
                (int) Math.round(mouseY * layout.guiScale()),
                layout.left(), layout.top(), originX, originZ, bpp,
                layout.guiScale(), level.dimension().location());
    }

    private MarkerDto markerAt(double mouseX, double mouseY, Layout layout,
                               double originX, double originZ) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return null;
        }
        int canvasX = (int) Math.round(mouseX * layout.guiScale());
        int canvasY = (int) Math.round(mouseY * layout.guiScale());
        int radius = 12 * layout.guiScale();
        double best = (double) radius * radius;
        MarkerDto nearest = null;
        ResourceLocation dimension = level.dimension().location();
        for (MarkerDto marker : MarkerClient.visibleMarkers(dimension)) {
            Vec3 world = markerWorld(level, marker);
            int px = layout.left() + MapProjection.pixel(world.x, originX, bpp);
            int py = layout.top() + MapProjection.pixel(world.z, originZ, bpp);
            double distance = (double) (px - canvasX) * (px - canvasX)
                    + (double) (py - canvasY) * (py - canvasY);
            if (distance < best) {
                best = distance;
                nearest = marker;
            }
        }
        return nearest;
    }

    /** 标记在地图上的当前世界位置：sable 锚点随船姿态重算，普通标记原样。 */
    private static Vec3 markerWorld(ClientLevel level, MarkerDto marker) {
        if (level == null) {
            return new Vec3(marker.x(), marker.y(), marker.z());
        }
        Vec3 resolved = MapHooks.INSTANCE.resolveMarker(level, marker, 1.0f);
        return resolved != null ? resolved : new Vec3(marker.x(), marker.y(), marker.z());
    }

    private void openEditor(MarkerDto marker) {
        if (!MarkerClient.canEdit(marker)) {
            return; // 临时标记 / 别人分享的标记：不可编辑
        }
        Minecraft.getInstance().setScreen(new MarkerEditScreen(marker));
    }

    private void handleRightClick(double mouseX, double mouseY, Layout layout,
                                  double originX, double originZ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        int canvasX = (int) Math.round(mouseX * layout.guiScale());
        int canvasY = (int) Math.round(mouseY * layout.guiScale());
        menuWorldX = (int) Math.floor(MapProjection.world(canvasX - layout.left(), originX, bpp));
        menuWorldZ = (int) Math.floor(MapProjection.world(canvasY - layout.top(), originZ, bpp));
        MarkerDto marker = markerAt(mouseX, mouseY, layout, originX, originZ);
        StructureEntry structure = marker == null
                ? structureAt(mouseX, mouseY, layout, originX, originZ) : null;
        menuEntries.clear();
        if (marker != null) {
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
                    () -> openCreateEditor(menuWorldX, menuWorldZ)));
            menuEntries.add(new MenuEntry(Component.translatable(
                    "catoxidesbattlerebuild.map.menu.ping"),
                    () -> createPingAt(menuWorldX, menuWorldZ)));
            if (structure != null && canTeleport()) {
                menuEntries.add(new MenuEntry(Component.translatable(
                        "catoxidesbattlerebuild.map.menu.tp_structure",
                        StructureClient.name(structure)),
                        () -> teleportTo(structure.blockX(), structure.blockZ())));
            }
        }
        // 作弊模式才给传送；正常模式隐藏（没有权限的玩家也不会看到一条点了没反应的菜单）
        if (canTeleport()) {
            menuEntries.add(new MenuEntry(Component.translatable(
                    "catoxidesbattlerebuild.map.menu.tp"),
                    () -> teleportTo(menuWorldX, menuWorldZ)));
        }
        if (menuEntries.isEmpty()) {
            menuOpen = false;
            return;
        }
        int menuWidth = menuWidth();
        int uiX = (int) Math.round(layout.toUiX(mouseX));
        int uiY = (int) Math.round(layout.toUiY(mouseY));
        menuX = Math.max(4, Math.min(uiX, layout.uiWidth() - menuWidth - 4));
        menuY = Math.max(4, Math.min(uiY,
                layout.uiHeight() - BOTTOM_BAR_GUI - menuHeight() - 4));
        layerPanelOpen = false;
        menuOpen = true;
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
        if (mouseX < menuX || mouseX >= menuX + menuWidth() || mouseY < menuY + 2) {
            return -1;
        }
        int row = (int) ((mouseY - (menuY + 2)) / MENU_ROW);
        return row < menuEntries.size() ? row : -1;
    }

    private void renderMenu(GuiGraphics graphics, Minecraft minecraft, int mouseX, int mouseY) {
        int width = menuWidth();
        int height = menuHeight();
        graphics.fill(menuX, menuY, menuX + width, menuY + height, 0xF0101010);
        graphics.fill(menuX, menuY, menuX + width, menuY + 1, 0xFF5A5A5A);
        graphics.fill(menuX, menuY + height - 1, menuX + width, menuY + height, 0xFF000000);
        for (int i = 0; i < menuEntries.size(); i++) {
            int rowY = menuY + 2 + i * MENU_ROW;
            boolean hover = mouseX >= menuX && mouseX < menuX + width
                    && mouseY >= rowY && mouseY < rowY + MENU_ROW;
            if (hover) {
                graphics.fill(menuX + 1, rowY, menuX + width - 1, rowY + MENU_ROW, 0x30FFFFFF);
            }
            graphics.drawString(minecraft.font, menuEntries.get(i).label().getString(),
                    menuX + 8, rowY + 4, hover ? 0xFFFFFFFF : 0xFFCCCCCC, false);
        }
    }

    private void deleteMarker(MarkerDto marker) {
        PacketDistributor.sendToServer(new MarkerRemovePayload(marker.id()));
    }

    /** 设置路径点不直接建：先打开编辑屏填名字/图标，保存时才发创建包（未加载区域也能设）。 */
    private void openCreateEditor(int worldX, int worldZ) {
        Minecraft.getInstance().setScreen(new MarkerEditScreen(worldX, worldZ, layer));
    }

    private void openCreateEditorAt(double mouseX, double mouseY, Layout layout,
                                    double originX, double originZ) {
        int canvasX = (int) Math.round(mouseX * layout.guiScale());
        int canvasY = (int) Math.round(mouseY * layout.guiScale());
        int worldX = (int) Math.floor(MapProjection.world(canvasX - layout.left(), originX, bpp));
        int worldZ = (int) Math.floor(MapProjection.world(canvasY - layout.top(), originZ, bpp));
        openCreateEditor(worldX, worldZ);
    }

    private void createPingAt(int worldX, int worldZ) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        MapStore.StackSample sample = MapStore.sampleStack(level.dimension().location(),
                MapLayerStack.sampleOrder(layers, layer), worldX, worldZ);
        int surfaceY = sample.height();
        Double deckY = MapHooks.INSTANCE.surfaceY(level, worldX, worldZ);
        double y = deckY != null ? deckY : (surfaceY == Integer.MIN_VALUE ? 64.0 : surfaceY);
        // 甲板锚点用顶面下一格探测：sub-level 包围盒最高到甲板方块本身
        Vec3 probe = new Vec3(worldX + 0.5, deckY != null ? y - 1.0 : y, worldZ + 0.5);
        CompoundTag data = new CompoundTag();
        Vec3 world = MapHooks.INSTANCE.resolveCreate(level, probe, null, data);
        ResourceLocation icon = ResourceLocation.withDefaultNamespace("nether_star");
        PacketDistributor.sendToServer(new MarkerCreatePayload(
                MarkerType.PING.name(), icon, world.x, world.y, world.z,
                -1, -1, "map:" + worldX + "," + worldZ, data, false));
    }

    /** 原版命令权限 2 = 单人开了作弊 / 服务器 op。 */
    private static boolean canTeleport() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    private void teleportTo(int worldX, int worldZ) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || minecraft.player.connection == null) {
            return;
        }
        MapStore.StackSample sample = MapStore.sampleStack(level.dimension().location(),
                MapLayerStack.sampleOrder(layers, layer), worldX, worldZ);
        int surfaceY = sample.height();
        // 未加载区域没有地表高度：按海平面走，落地后再微调；明确的路径点用 Y 字段
        int y = surfaceY == Integer.MIN_VALUE ? level.getSeaLevel() : surfaceY + 1;
        minecraft.player.connection.sendCommand(
                "tp " + (worldX + 0.5) + " " + y + " " + (worldZ + 0.5));
    }

    // ---------------------------------------------------------------- input

    private boolean insideView(double mouseX, double mouseY, Layout layout) {
        int canvasX = (int) Math.round(mouseX * layout.guiScale());
        int canvasY = (int) Math.round(mouseY * layout.guiScale());
        return canvasX >= layout.left() && canvasX < layout.left() + layout.viewWidth()
                && canvasY >= layout.top() && canvasY < layout.top() + layout.viewHeight();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Minecraft minecraft = Minecraft.getInstance();
        mouseDown = true;
        Layout layout = layout(minecraft, this.width, this.height);
        double uiMouseX = layout.toUiX(mouseX);
        double uiMouseY = layout.toUiY(mouseY);

        if (menuOpen) {
            int row = menuRowAt(uiMouseX, uiMouseY);
            MenuEntry entry = row >= 0 ? menuEntries.get(row) : null;
            menuOpen = false;
            if (entry != null) {
                entry.action().run();
            }
            return true;
        }

        for (MapUiButton widget : buttons) {
            if (widget.click(uiMouseX, uiMouseY, button)) {
                return true;
            }
        }

        if (layerPanelOpen && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int panelTop = panelTop(minecraft);
            int rows = Math.min(panelRows(), layers.size());
            if (uiMouseX >= PANEL_X && uiMouseX < PANEL_X + PANEL_WIDTH
                    && uiMouseY >= panelTop + 2 && uiMouseY < panelTop + 2 + rows * PANEL_ROW) {
                int row = layerScroll + (int) ((uiMouseY - (panelTop + 2)) / PANEL_ROW);
                if (row >= 0 && row < layers.size()) {
                    layer = layers.get(row);
                    MapLayerSelection.select(layer);
                }
                return true;
            }
            // 点在面板外：面板先关，这次点击继续交给地图（平移/放标记）
            layerPanelOpen = false;
        }

        if (!insideView(mouseX, mouseY, layout)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        double originX = MapProjection.origin(centerX, layout.viewWidth(), bpp);
        double originZ = MapProjection.origin(centerZ, layout.viewHeight(), bpp);

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (placing) {
                placing = false;
                buildButtons();
                openCreateEditorAt(mouseX, mouseY, layout, originX, originZ);
                return true;
            }
            dragging = true;
            moved = false;
            lastDragCanvasX = mouseX * layout.guiScale();
            lastDragCanvasY = mouseY * layout.guiScale();
            pressedMarker = markerAt(mouseX, mouseY, layout, originX, originZ);
            // 只是按一下（选中标记 / 准备放置）不打断跟随；真正拖动才取消
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            handleRightClick(mouseX, mouseY, layout, originX, originZ);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mouseDown = false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragging) {
            dragging = false;
            if (!moved && pressedMarker != null) {
                openEditor(pressedMarker);
            }
            pressedMarker = null;
            moved = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        Minecraft minecraft = Minecraft.getInstance();
        Layout layout = layout(minecraft, this.width, this.height);
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            double canvasX = mouseX * layout.guiScale();
            double canvasY = mouseY * layout.guiScale();
            if (Math.abs(canvasX - lastDragCanvasX) > 3 || Math.abs(canvasY - lastDragCanvasY) > 3) {
                moved = true;
            }
            if (moved) {
                centerX -= (canvasX - lastDragCanvasX) * bpp;
                centerZ -= (canvasY - lastDragCanvasY) * bpp;
                lastDragCanvasX = canvasX;
                lastDragCanvasY = canvasY;
                follow = false;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** 滚轮缩放，锚点是鼠标下的世界坐标：缩放前后那一点不动。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        Minecraft minecraft = Minecraft.getInstance();
        Layout layout = layout(minecraft, this.width, this.height);
        if (layerPanelOpen && layout.toUiX(mouseX) >= PANEL_X
                && layout.toUiX(mouseX) < PANEL_X + PANEL_WIDTH
                && layout.toUiY(mouseY) >= panelTop(minecraft)) {
            layerScroll = Math.max(0, Math.min(layers.size() - 1, layerScroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        double mouseCanvasX = mouseX * layout.guiScale() - layout.left();
        double mouseCanvasY = mouseY * layout.guiScale() - layout.top();
        double originX = MapProjection.origin(centerX, layout.viewWidth(), bpp);
        double originZ = MapProjection.origin(centerZ, layout.viewHeight(), bpp);
        double worldX = MapProjection.world(mouseCanvasX, originX, bpp);
        double worldZ = MapProjection.world(mouseCanvasY, originZ, bpp);

        bpp = scrollY > 0 ? MapZoom.stepIn(bpp) : MapZoom.stepOut(bpp);
        centerX = worldX - (mouseCanvasX - layout.viewWidth() / 2.0) * bpp;
        centerZ = worldZ - (mouseCanvasY - layout.viewHeight() / 2.0) * bpp;
        follow = false;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_L) {
            toggleLayers();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F) {
            follow = !follow;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_M) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void toggleLayers() {
        layerPanelOpen = !layerPanelOpen;
        layerScroll = 0;
    }

    private void togglePlacing() {
        placing = !placing;
        buildButtons();
    }

    private void toggleStructures() {
        StructureClient.setVisible(!StructureClient.isVisible());
        buildButtons();
    }

    private void zoomIn() {
        bpp = MapZoom.stepIn(bpp);
        follow = false;
    }

    private void zoomOut() {
        bpp = MapZoom.stepOut(bpp);
        follow = false;
    }

    private void recenter() {
        follow = true;
    }
}
