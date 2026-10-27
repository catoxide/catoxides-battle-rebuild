package example.contentpack.map.client;

import example.contentpack.map.MapConfig;
import example.contentpack.map.MapLayer;
import example.contentpack.map.MapStore;
import example.contentpack.map.MapTile;
import example.contentpack.map.marker.MarkerDto;
import example.contentpack.map.marker.MarkerType;
import example.contentpack.map.network.MarkerCreatePayload;
import example.contentpack.map.network.MarkerRemovePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 全屏世界地图：源瓦片 + GPU 缩放（图片编辑器模型）+ 工具栏 UI。
 *
 * <p>底部：标记管理 / 放置 / 图层 / 设置；右侧：缩放、归中、导出；右上角显示当前缩放。
 * 按钮有悬停/按下亮度反馈；鼠标悬停标记会高亮。
 */
public final class MapScreen extends Screen {

    private static final int[] ZOOMS = {1, 2, 4, 8, 16, 32};
    private static final int MAX_LOAD_REQUESTS = 320;
    private static final int MARGIN = 40;
    private static final int BOTTOM_BAR = 28;

    private final TileLodTextures textures = new TileLodTextures();
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

    private String layerOverride;
    private String lastLayer;
    private ResourceLocation lastDimension;

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
        buttons.add(new MapUiButton(bx, barY, 90, 20, "Layers", () -> hint("Layers: coming soon")));
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
        int bpp = ZOOMS[zoomIndex];
        int span = view * bpp;
        double minWorldX = centerX - span / 2.0;
        double minWorldZ = centerZ - span / 2.0;
        String layer = currentLayer(level);
        ResourceLocation dimension = level.dimension().location();
        boolean prediction = MapLayer.TOP.equals(layer);
        int lod = lodFor(bpp);

        MapMinimap.requestVisibleTiles(dimension, layer,
                (int) Math.floor(minWorldX), (int) Math.floor(minWorldZ), span + 256,
                MAX_LOAD_REQUESTS);
        if (prediction) {
            MapBiomeLayer.ensureVisible(dimension,
                    (int) Math.floor(minWorldX), (int) Math.floor(minWorldZ), span + 256);
        }

        graphics.fill(0, 0, this.width, this.height, 0xD0101010);
        graphics.fill(left - 1, top - 1, left + view + 1, top + view + 1, 0xFF000000);

        int minTileX = MapTile.tileCoord((int) Math.floor(minWorldX));
        int maxTileX = MapTile.tileCoord((int) Math.floor(minWorldX + span));
        int minTileZ = MapTile.tileCoord((int) Math.floor(minWorldZ));
        int maxTileZ = MapTile.tileCoord((int) Math.floor(minWorldZ + span));
        int minY = level.getMinBuildHeight();
        int heightRange = Math.max(1, level.getMaxBuildHeight() - minY - 1);
        int lodSize = MapTile.SIZE >> lod;
        int screenTile = MapTile.SIZE / bpp;

        graphics.enableScissor(left, top, left + view, top + view);
        for (MapStore.TileAt at : MapStore.visibleTiles(
                dimension, layer, minTileX, maxTileX, minTileZ, maxTileZ)) {
            ResourceLocation texture = textures.get(at, dimension, minY, heightRange, lod,
                    prediction, prediction ? MapBiomeLayer.version() : 0L);
            int screenX = left + (int) Math.floor(
                    (at.x() * MapTile.SIZE - minWorldX) / bpp);
            int screenZ = top + (int) Math.floor(
                    (at.z() * MapTile.SIZE - minWorldZ) / bpp);
            graphics.blit(texture, screenX, screenZ, 0, 0,
                    screenTile, screenTile, lodSize, lodSize);
        }
        graphics.disableScissor();

        MarkerHud.renderFullmap(graphics, minecraft, left, top, view, bpp,
                minWorldX, minWorldZ, dimension, partialTick);
        drawPlayerMarker(graphics, minecraft, left, top, view, minWorldX, minWorldZ, bpp);
        highlightHoveredMarker(graphics, minecraft, mouseX, mouseY, left, top, minWorldX, minWorldZ, bpp);

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
                + "   (" + span + " blocks)"
                + "   [" + MapLayer.label(layer) + "]";
        graphics.drawCenteredString(minecraft.font, info, this.width / 2,
                top + view + 8, 0xFFFFFFFF);
        graphics.drawCenteredString(minecraft.font,
                Component.translatable("catoxidesbattlerebuild.map.hint"),
                this.width / 2, top + view + 22, 0xFFAAAAAA);

        if (MapConfig.showBlockInfo() && !pressing && insideView(mouseX, mouseY)) {
            int worldX = (int) Math.floor(minWorldX + (mouseX - left) * (double) bpp);
            int worldZ = (int) Math.floor(minWorldZ + (mouseY - top) * (double) bpp);
            MapProbe.Info probe = MapProbe.probe(level, worldX, worldZ, layer);
            if (probe != null) {
                drawProbeTooltip(graphics, minecraft, mouseX, mouseY, probe);
            }
        }
    }

    private void drawZoomBox(GuiGraphics graphics, Minecraft minecraft, int top) {
        int x = this.width - 96;
        int y = top + 10;
        graphics.fill(x, y, x + 62, y + 18, 0xC0101010);
        graphics.drawCenteredString(minecraft.font, ZOOMS[zoomIndex] + " b/px",
                x + 31, y + 5, 0xFFFFFFFF);
    }

    private void highlightHoveredMarker(GuiGraphics graphics, Minecraft minecraft,
                                        int mouseX, int mouseY, int left, int top,
                                        double minWorldX, double minWorldZ, int bpp) {
        if (!insideView(mouseX, mouseY)) {
            return;
        }
        MarkerDto hovered = markerAt(mouseX, mouseY);
        if (hovered == null) {
            return;
        }
        int px = left + (int) Math.floor((hovered.x() - minWorldX) / bpp);
        int py = top + (int) Math.floor((hovered.z() - minWorldZ) / bpp);
        if (hovered.type().waypoint()) {
            // 不要框：把白色三角直接压成灰色，像被按住
            MarkerHud.drawTriangle(graphics, px, py, 5, 0xFF8A8A8A);
        } else {
            graphics.fill(px - 8, py - 8, px + 8, py + 8, 0x66000000);
            String label = hovered.data().getString("label");
            if (!label.isBlank()) {
                graphics.drawCenteredString(minecraft.font, label, px, py - 20, 0xFFFFFFFF);
            }
        }
    }

    private void drawPlayerMarker(GuiGraphics graphics, Minecraft minecraft,
                                  int left, int top, int view,
                                  double minWorldX, double minWorldZ, int bpp) {
        int playerX = left + (int) Math.floor(
                (minecraft.player.getX() - minWorldX) / bpp);
        int playerZ = top + (int) Math.floor(
                (minecraft.player.getZ() - minWorldZ) / bpp);
        if (playerX < left || playerX >= left + view || playerZ < top || playerZ >= top + view) {
            return;
        }
        graphics.fill(playerX - 2, playerZ - 2, playerX + 3, playerZ + 3, 0xFFFF4040);
    }

    private void drawProbeTooltip(GuiGraphics graphics, Minecraft minecraft,
                                  int mouseX, int mouseY, MapProbe.Info probe) {
        List<String> lines = new ArrayList<>();
        if (probe.fromMap()) {
            lines.add(probe.x() + ", " + probe.z());
            if (probe.block() != null) {
                lines.add(probe.block());
            }
            if (probe.biome() != null) {
                lines.add("~ " + probe.biome());
            }
        } else {
            lines.add(probe.x() + ", " + probe.y() + ", " + probe.z());
            if (probe.block() != null) {
                lines.add(probe.block());
            }
            if (probe.biome() != null) {
                lines.add(probe.biome());
            }
        }
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, minecraft.font.width(line));
        }
        width += 8;
        int height = lines.size() * 10 + 6;
        int x = mouseX + 12;
        int y = mouseY + 12;
        if (x + width > this.width - 4) {
            x = mouseX - width - 12;
        }
        if (y + height > this.height - 4) {
            y = mouseY - height - 12;
        }
        graphics.fill(x, y, x + width, y + height, 0xE8101010);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(minecraft.font, lines.get(i), x + 4, y + 3 + i * 10,
                    0xFFFFFFFF, true);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseDown = true;
        for (MapUiButton widget : buttons) {
            if (widget.click(mouseX, mouseY, button)) {
                return true;
            }
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
            followPlayer = false;
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
        int bpp = ZOOMS[zoomIndex];
        int span = viewSize() * bpp;
        double minWorldX = centerX - span / 2.0;
        double minWorldZ = centerZ - span / 2.0;
        int left = left();
        int top = top();
        MarkerDto nearest = null;
        double best = 12.0 * 12.0;
        for (MarkerDto marker : MarkerClient.visibleMarkers(level.dimension().location())) {
            int px = left + (int) Math.floor((marker.x() - minWorldX) / bpp);
            int py = top + (int) Math.floor((marker.z() - minWorldZ) / bpp);
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
                int bpp = ZOOMS[zoomIndex];
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
        Minecraft.getInstance().setScreen(new MarkerEditScreen(marker));
    }

    /** 右键：点到标记 → 删除；空处 → 临时点；Shift+右键 → 永久点。 */
    private void handleRightClick(double mouseX, double mouseY) {
        MarkerDto nearest = markerAt(mouseX, mouseY);
        if (nearest != null) {
            PacketDistributor.sendToServer(new MarkerRemovePayload(nearest.id()));
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean permanent = minecraft.player != null && minecraft.player.isShiftKeyDown();
        createMapMarker(mouseX, mouseY, permanent);
    }

    private void createMapMarker(double mouseX, double mouseY, boolean permanent) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        int bpp = ZOOMS[zoomIndex];
        int span = viewSize() * bpp;
        double minWorldX = centerX - span / 2.0;
        double minWorldZ = centerZ - span / 2.0;
        int worldX = (int) Math.floor(minWorldX + (mouseX - left()) * bpp);
        int worldZ = (int) Math.floor(minWorldZ + (mouseY - top()) * bpp);
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
        int view = viewSize();
        int left = left();
        int top = top();
        int oldBpp = ZOOMS[zoomIndex];
        int next = Math.max(0, Math.min(ZOOMS.length - 1,
                zoomIndex + (scrollY > 0 ? -1 : 1)));
        if (next == zoomIndex) {
            return true;
        }
        double relX = mouseX - (left + view / 2.0);
        double relZ = mouseY - (top + view / 2.0);
        double worldX = centerX + relX * oldBpp;
        double worldZ = centerZ + relZ * oldBpp;
        zoomIndex = next;
        int newBpp = ZOOMS[zoomIndex];
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

    @Override
    public void removed() {
        textures.clear();
        super.removed();
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
            textures.clear();
            lastDimension = dimension;
            lastLayer = layer;
        }
    }

    private void cycleLayer(int delta) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        List<String> layers = MapMinimap.selectableLayers(level);
        int index = layers.indexOf(currentLayer(level));
        if (index < 0) {
            index = 0;
        }
        layerOverride = layers.get(Math.floorMod(index + delta, layers.size()));
        syncView();
    }

    private String currentLayer(ClientLevel level) {
        return layerOverride != null ? layerOverride : MapMinimap.activeLayer(level);
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

    private static int lodFor(int bpp) {
        int level = Integer.numberOfTrailingZeros(Integer.highestOneBit(Math.max(1, bpp)));
        return Math.min(TileLodTextures.MAX_LOD, level);
    }

    private static int nearestZoomIndex(int bpp) {
        int best = 0;
        for (int i = 0; i < ZOOMS.length; i++) {
            if (ZOOMS[i] <= bpp) {
                best = i;
            }
        }
        return best;
    }
}
