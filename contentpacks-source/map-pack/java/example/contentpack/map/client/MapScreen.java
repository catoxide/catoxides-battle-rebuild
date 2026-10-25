package example.contentpack.map.client;

import com.mojang.blaze3d.platform.NativeImage;
import example.contentpack.map.MapConfig;
import example.contentpack.map.MapLayer;
import example.contentpack.map.MapStorage;
import example.contentpack.map.MapStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 全屏世界地图：**视口重采样 + 离散缩放档位**。
 *
 * <p>可见区域从 {@link MapStore} 重新采样成一张 {@value #VIEWPORT_TEX}×{@value #VIEWPORT_TEX}
 * 的纹理，再整体缩放到屏幕。任意档位都是整张图缩放，没有瓦片取样中心、没有接缝。
 *
 * <p>交互：左键拖动（拖动时旧纹理偏移绘制，松手重采样）· 滚轮切换 1/2/4/8/16/32 档 ·
 * C 回到玩家 · PgUp/PgDn 切层 · Esc 关闭。
 */
public final class MapScreen extends Screen {

    private static final int[] ZOOMS = {1, 2, 4, 8, 16, 32};
    private static final int VIEWPORT_TEX = 512;
    private static final int MAX_LOAD_REQUESTS = 256;
    private static final int MARGIN = 40;
    private static final long REBUILD_INTERVAL_NANOS = 200_000_000L;

    private NativeImage viewportImage;
    private DynamicTexture viewportTexture;
    private ResourceLocation viewportLocation;
    private int textureSeq;

    private int textureOriginX;
    private int textureOriginZ;
    private int textureStep = 1;
    private long lastRebuildNanos;
    private long lastGeneration = -1;
    private boolean needRebuild = true;

    private int zoomIndex;
    /** 视口中心保持 double，避免每次缩放取整累积误差。 */
    private double centerX;
    private double centerZ;
    private boolean followPlayer = true;
    private boolean dragging;
    private String layerOverride;
    private String lastLayer;
    private ResourceLocation lastDimension;
    private long lastBiomeVersion = -1;

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
        rebuildViewport();
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

        maybeRebuild();

        graphics.fill(0, 0, this.width, this.height, 0xD0101010);
        graphics.fill(left - 1, top - 1, left + view + 1, top + view + 1, 0xFF000000);

        int drawSize = (int) Math.round(VIEWPORT_TEX * textureStep / (double) bpp);
        int screenX = left + (int) Math.floor((textureOriginX - minWorldX) / bpp);
        int screenY = top + (int) Math.floor((textureOriginZ - minWorldZ) / bpp);

        graphics.enableScissor(left, top, left + view, top + view);
        if (viewportLocation != null) {
            graphics.blit(viewportLocation, screenX, screenY, 0, 0,
                    drawSize, drawSize, VIEWPORT_TEX, VIEWPORT_TEX);
        }
        graphics.disableScissor();

        drawPlayerMarker(graphics, minecraft, left, top, view, screenX, screenY, drawSize);

        String info = (int) Math.floor(centerX) + ", " + (int) Math.floor(centerZ)
                + "   (" + span + " blocks)"
                + "   [" + MapLayer.label(layer) + "]";
        graphics.drawCenteredString(minecraft.font, info, this.width / 2,
                top + view + 8, 0xFFFFFFFF);
        graphics.drawCenteredString(minecraft.font,
                Component.translatable("catoxidesbattlerebuild.map.hint"),
                this.width / 2, top + view + 22, 0xFFAAAAAA);

        if (MapConfig.showBlockInfo() && !dragging && insideView(mouseX, mouseY)) {
            int worldX = (int) Math.floor(minWorldX + (mouseX - left) * (double) bpp);
            int worldZ = (int) Math.floor(minWorldZ + (mouseY - top) * (double) bpp);
            MapProbe.Info probe = MapProbe.probe(level, worldX, worldZ, layer);
            if (probe != null) {
                drawProbeTooltip(graphics, minecraft, mouseX, mouseY, probe);
            }
        }
    }

    private void maybeRebuild() {
        long now = System.nanoTime();
        if (needRebuild) {
            rebuildViewport();
            return;
        }
        long generation = MapStorage.generation();
        if (now - lastRebuildNanos < REBUILD_INTERVAL_NANOS) {
            return;
        }
        if (generation != lastGeneration || (followPlayer && !dragging)) {
            rebuildViewport();
        }
    }

    private void rebuildViewport() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || MapWorld.id() == null) {
            return;
        }
        int view = viewSize();
        int bpp = ZOOMS[zoomIndex];
        int span = view * bpp;
        int step = Math.max(1, (span + VIEWPORT_TEX - 1) / VIEWPORT_TEX);
        int textureSpan = step * VIEWPORT_TEX;
        int originX = (int) Math.floor(centerX - textureSpan / 2.0);
        int originZ = (int) Math.floor(centerZ - textureSpan / 2.0);
        String layer = currentLayer(level);
        ResourceLocation dimension = level.dimension().location();
        boolean prediction = MapLayer.TOP.equals(layer);

        MapMinimap.requestVisibleTiles(dimension, layer, originX, originZ, textureSpan,
                MAX_LOAD_REQUESTS);
        if (prediction) {
            MapBiomeLayer.ensureVisible(dimension, originX, originZ, textureSpan);
        }

        int minY = level.getMinBuildHeight();
        int heightRange = Math.max(1, level.getMaxBuildHeight() - minY - 1);
        NativeImage image = new NativeImage(VIEWPORT_TEX, VIEWPORT_TEX, true);
        boolean[] known = new boolean[VIEWPORT_TEX * VIEWPORT_TEX];

        for (int py = 0; py < VIEWPORT_TEX; py++) {
            int worldZ = originZ + py * step;
            for (int px = 0; px < VIEWPORT_TEX; px++) {
                int worldX = originX + px * step;
                int argb = MapStore.sampleColor(dimension, layer, worldX, worldZ);
                if (argb == 0) {
                    continue;
                }
                int y = MapStore.sampleHeight(dimension, layer, worldX, worldZ);
                int fluid = MapStore.sampleFluid(dimension, layer, worldX, worldZ);
                image.setPixelRGBA(px, py, MapMinimap.toNativeAbgr(
                        MapMinimap.applyShade(argb, y, minY, heightRange, fluid)));
                known[py * VIEWPORT_TEX + px] = true;
            }
        }

        if (prediction) {
            // 缩得太远时关掉描边，避免变成密集网格
            boolean outline = MapConfig.predictionOutline() && step <= 8;
            BiomeOverlay.paint(image, dimension, originX, originZ, VIEWPORT_TEX, step,
                    (px, py) -> known[py * VIEWPORT_TEX + px], outline);
        }

        if (viewportLocation != null) {
            minecraft.getTextureManager().release(viewportLocation);
        }
        viewportImage = image;
        viewportTexture = new DynamicTexture(image);
        viewportTexture.setFilter(true, false);
        viewportLocation = minecraft.getTextureManager()
                .register("map_pack_viewport_" + (textureSeq++), viewportTexture);

        textureOriginX = originX;
        textureOriginZ = originZ;
        textureStep = step;
        lastRebuildNanos = System.nanoTime();
        lastGeneration = MapStorage.generation();
        needRebuild = false;
    }

    /** 玩家标记按纹理自身映射计算，缩放/拖动时不会和地图内容错位。 */
    private void drawPlayerMarker(GuiGraphics graphics, Minecraft minecraft,
                                  int left, int top, int view,
                                  int screenX, int screenY, int drawSize) {
        double scale = drawSize / (double) Math.max(1, VIEWPORT_TEX * textureStep);
        int playerX = screenX + (int) Math.round(
                (minecraft.player.getX() - textureOriginX) * scale);
        int playerZ = screenY + (int) Math.round(
                (minecraft.player.getZ() - textureOriginZ) * scale);
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
        if (button == 0 && insideView(mouseX, mouseY)) {
            dragging = true;
            followPlayer = false;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (dragging) {
            int bpp = ZOOMS[zoomIndex];
            centerX -= dragX * bpp;
            centerZ -= dragY * bpp;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            needRebuild = true;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
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
        // 以光标下的世界点为锚缩放（double 中心，避免取整漂移）
        double relX = mouseX - (left + view / 2.0);
        double relZ = mouseY - (top + view / 2.0);
        double worldX = centerX + relX * oldBpp;
        double worldZ = centerZ + relZ * oldBpp;
        zoomIndex = next;
        int newBpp = ZOOMS[zoomIndex];
        centerX = worldX - relX * newBpp;
        centerZ = worldZ - relZ * newBpp;
        needRebuild = true;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_C) {
            followPlayer = true;
            needRebuild = true;
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
        Minecraft minecraft = Minecraft.getInstance();
        if (viewportLocation != null) {
            minecraft.getTextureManager().release(viewportLocation);
            viewportLocation = null;
        }
        viewportTexture = null;
        viewportImage = null;
        super.removed();
    }

    /** 跟随玩家 + 层/维度/预测版本变化时触发重采样。 */
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
        long biomeVersion = MapBiomeLayer.version();
        if (!dimension.equals(lastDimension) || !layer.equals(lastLayer)
                || biomeVersion != lastBiomeVersion) {
            lastDimension = dimension;
            lastLayer = layer;
            lastBiomeVersion = biomeVersion;
            needRebuild = true;
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
        needRebuild = true;
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
        return Math.min(this.width, this.height) - MARGIN;
    }

    private int left() {
        return (this.width - viewSize()) / 2;
    }

    private int top() {
        return (this.height - viewSize()) / 2;
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
