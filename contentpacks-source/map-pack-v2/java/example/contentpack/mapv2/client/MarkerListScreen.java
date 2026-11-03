package example.contentpack.mapv2.client;

import example.contentpack.mapv2.marker.MarkerData;
import example.contentpack.mapv2.marker.MarkerDto;
import example.contentpack.mapv2.network.MarkerAdoptPayload;
import example.contentpack.mapv2.network.MarkerRemovePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 标记管理：列出标记；自己/可管理的可编辑删除，别人分享的永久标记可以保存到自己的地图。 */
public final class MarkerListScreen extends Screen {

    private static final int LIST_TOP = 36;
    private static final int ROW_HEIGHT = 24;
    private static final int MAX_ROWS = 12;
    private static final int BUTTON_WIDTH = 48;
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_GAP = 6;

    private final List<MarkerDto> markers = new ArrayList<>();
    /** 本次界面里已经点过「保存」的分享标记：按钮消失，等同步回来的副本出现在下次列表里。 */
    private final Set<UUID> adopted = new HashSet<>();
    private int scroll;

    public MarkerListScreen() {
        super(Component.translatable("catoxidesbattlerebuild.map.markers.title"));
    }

    @Override
    protected void init() {
        markers.clear();
        // 路径点管理：临时 PING 会自己过期，不进这个列表
        markers.addAll(MarkerClient.allMarkers().stream()
                .filter(marker -> !marker.type().temporaryWaypoint())
                .toList());
        markers.sort(Comparator.comparingLong(MarkerDto::createdAt).reversed());
        scroll = Math.max(0, Math.min(scroll, Math.max(0, markers.size() - MAX_ROWS)));
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                        button -> Minecraft.getInstance().setScreen(new MapV2Screen()))
                .bounds(this.width / 2 - 60, this.height - 30, 120, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 留空：原版会在地图界面后面糊一层世界，这里不要
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xE0101010);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

        int shown = Math.min(MAX_ROWS, markers.size() - scroll);
        for (int i = 0; i < shown; i++) {
            MarkerDto marker = markers.get(scroll + i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            if (marker.id().equals(hoveredId(mouseX, mouseY))) {
                graphics.fill(8, y - 2, this.width - 8, y + ROW_HEIGHT - 4, 0x30FFFFFF);
            }
            // 生物/玩家显示 16×16 的脸，和物品图标槽（12,y 起的 16×16）完全对齐
            MarkerHud.drawIcon(graphics, marker, 20, y + 8, 1.0f, 1f);
            String name = marker.data().getString(MarkerData.KEY_LABEL);
            if (name.isBlank()) {
                name = MarkerData.hasItem(marker)
                        ? MarkerClient.iconStack(marker).getHoverName().getString()
                        : typeName(marker);
            }
            graphics.drawString(this.font, name, 36, y + 4, 0xFFFFFFFF, true);
            String meta = typeName(marker) + "  " + distanceText(marker);
            graphics.drawString(this.font, meta, 200, y + 4, 0xFF9A9A9A, false);

            drawActions(graphics, mouseX, mouseY, marker, y);
        }

        if (markers.size() > MAX_ROWS) {
            String footer = (scroll + 1) + "-" + (scroll + shown) + " / " + markers.size()
                    + "   " + Component.translatable(
                            "catoxidesbattlerebuild.map.ui.scroll_hint").getString();
            graphics.drawString(this.font, footer, 12, LIST_TOP + MAX_ROWS * ROW_HEIGHT,
                    0xFF9A9A9A, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 每行右侧的动作按钮：分享的永久标记「保存」、自己的「编辑」/「删除」。 */
    private void drawActions(GuiGraphics graphics, int mouseX, int mouseY,
                             MarkerDto marker, int y) {
        if (canAdopt(marker)) {
            drawSmallButton(graphics, mouseX, mouseY, adoptX(), y + 3,
                    BUTTON_WIDTH, BUTTON_HEIGHT, Component.translatable(
                            "catoxidesbattlerebuild.map.markers.save").getString());
        }
        if (MarkerClient.canEdit(marker)) {
            drawSmallButton(graphics, mouseX, mouseY, editX(), y + 3,
                    BUTTON_WIDTH, BUTTON_HEIGHT, Component.translatable(
                            "catoxidesbattlerebuild.map.markers.edit").getString());
        }
        if (MarkerClient.canModify(marker)) {
            drawSmallButton(graphics, mouseX, mouseY, deleteX(), y + 3,
                    BUTTON_WIDTH, BUTTON_HEIGHT, Component.translatable(
                            "catoxidesbattlerebuild.map.markers.delete").getString());
        }
    }

    /** 别人分享的永久标记可以复制成自己的一份（永久化），复制后就归自己、可编辑。 */
    private boolean canAdopt(MarkerDto marker) {
        return marker.type().editable()
                && !MarkerClient.canModify(marker)
                && !adopted.contains(marker.id());
    }

    private static String typeName(MarkerDto marker) {
        return Component.translatable("catoxidesbattlerebuild.map.marker.type."
                + marker.type().name().toLowerCase(java.util.Locale.ROOT)).getString();
    }

    private void drawSmallButton(GuiGraphics graphics, int mouseX, int mouseY,
                                 int x, int y, int width, int height, String label) {
        boolean hover = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        graphics.fill(x, y, x + width, y + height, hover ? 0xFF3A3A3A : 0xFF242424);
        graphics.drawCenteredString(this.font, label, x + width / 2, y + (height - 8) / 2,
                hover ? 0xFFFFFFFF : 0xFFCCCCCC);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int shown = Math.min(MAX_ROWS, markers.size() - scroll);
            for (int i = 0; i < shown; i++) {
                int index = scroll + i;
                MarkerDto marker = markers.get(index);
                int y = LIST_TOP + i * ROW_HEIGHT;
                if (canAdopt(marker) && inside(mouseX, mouseY,
                        adoptX(), y + 3, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                    adopted.add(marker.id());
                    PacketDistributor.sendToServer(new MarkerAdoptPayload(marker.id()));
                    return true;
                }
                if (MarkerClient.canEdit(marker) && inside(mouseX, mouseY,
                        editX(), y + 3, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                    Minecraft.getInstance().setScreen(new MarkerEditScreen(marker));
                    return true;
                }
                if (MarkerClient.canModify(marker) && inside(mouseX, mouseY,
                        deleteX(), y + 3, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                    PacketDistributor.sendToServer(new MarkerRemovePayload(marker.id()));
                    MarkerClient.receive(new example.contentpack.mapv2.network.MarkerSyncPayload(
                            List.of(), List.of(marker.id()), false));
                    markers.remove(index);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private MarkerDto hoveredId(double mouseX, double mouseY) {
        int shown = Math.min(MAX_ROWS, markers.size() - scroll);
        for (int i = 0; i < shown; i++) {
            int y = LIST_TOP + i * ROW_HEIGHT;
            if (mouseY >= y - 2 && mouseY < y + ROW_HEIGHT - 4) {
                return markers.get(scroll + i);
            }
        }
        return null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            int max = Math.max(0, markers.size() - MAX_ROWS);
            scroll = Math.max(0, Math.min(max, scroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static boolean inside(double mouseX, double mouseY,
                                  int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private int adoptX() {
        return this.width - 170;
    }

    private int editX() {
        return this.width - 116;
    }

    private int deleteX() {
        return this.width - 62;
    }

    private String distanceText(MarkerDto marker) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return "";
        }
        double distance = Math.sqrt(player.distanceToSqr(marker.x(), marker.y(), marker.z()));
        return Math.round(distance) + "m";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
