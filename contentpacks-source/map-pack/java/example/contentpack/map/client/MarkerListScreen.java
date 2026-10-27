package example.contentpack.map.client;

import example.contentpack.map.marker.MarkerData;
import example.contentpack.map.marker.MarkerDto;
import example.contentpack.map.network.MarkerRemovePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 标记管理：列出标记，编辑 / 删除。 */
public final class MarkerListScreen extends Screen {

    private static final int LIST_TOP = 36;
    private static final int ROW_HEIGHT = 24;
    private static final int MAX_ROWS = 12;

    private final List<MarkerDto> markers = new ArrayList<>();

    public MarkerListScreen() {
        super(Component.translatable("catoxidesbattlerebuild.map.markers.title"));
    }

    @Override
    protected void init() {
        markers.clear();
        markers.addAll(MarkerClient.allMarkers());
        markers.sort(Comparator.comparingLong(MarkerDto::createdAt).reversed());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                        button -> Minecraft.getInstance().setScreen(new MapScreen()))
                .bounds(this.width / 2 - 60, this.height - 30, 120, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xE0101010);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

        int shown = Math.min(MAX_ROWS, markers.size());
        for (int i = 0; i < shown; i++) {
            MarkerDto marker = markers.get(i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            if (marker.id().equals(hoveredId(mouseX, mouseY))) {
                graphics.fill(8, y - 2, this.width - 8, y + ROW_HEIGHT - 4, 0x30FFFFFF);
            }
            String label = marker.data().getString(MarkerData.KEY_LABEL);
            String name;
            if (marker.type().waypoint() && !MarkerData.iconOverride(marker)) {
                // 默认地点标记：与地图/世界一致的白色三角形（TODO: 颜色可配置）
                MarkerHud.drawTriangle(graphics, 20, y + 16, 6, 0xFFFFFFFF);
                name = label.isBlank()
                        ? Component.translatable("catoxidesbattlerebuild.map.marker.type."
                        + marker.type().name().toLowerCase(java.util.Locale.ROOT)).getString()
                        : label;
            } else {
                ItemStack stack = MarkerClient.iconStack(marker);
                graphics.renderItem(stack, 12, y);
                name = label.isBlank() ? stack.getHoverName().getString() : label;
            }
            graphics.drawString(this.font, name, 36, y + 4, 0xFFFFFFFF, true);
            String meta = marker.type().name() + "  " + distanceText(marker);
            graphics.drawString(this.font, meta, 200, y + 4, 0xFF9A9A9A, false);

            drawSmallButton(graphics, mouseX, mouseY, editX(), y + 3, 48, 18,
                    Component.translatable("catoxidesbattlerebuild.map.markers.edit").getString());
            drawSmallButton(graphics, mouseX, mouseY, deleteX(), y + 3, 48, 18, "Del");
        }

        if (markers.size() > MAX_ROWS) {
            graphics.drawString(this.font, "+" + (markers.size() - MAX_ROWS) + " more",
                    12, LIST_TOP + MAX_ROWS * ROW_HEIGHT, 0xFF9A9A9A, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
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
            int shown = Math.min(MAX_ROWS, markers.size());
            for (int i = 0; i < shown; i++) {
                MarkerDto marker = markers.get(i);
                int y = LIST_TOP + i * ROW_HEIGHT;
                if (inside(mouseX, mouseY, editX(), y + 3, 48, 18)) {
                    Minecraft.getInstance().setScreen(new MarkerEditScreen(marker));
                    return true;
                }
                if (inside(mouseX, mouseY, deleteX(), y + 3, 48, 18)) {
                    PacketDistributor.sendToServer(new MarkerRemovePayload(marker.id()));
                    MarkerClient.receive(new example.contentpack.map.network.MarkerSyncPayload(
                            List.of(), List.of(marker.id()), false));
                    markers.remove(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private MarkerDto hoveredId(double mouseX, double mouseY) {
        int shown = Math.min(MAX_ROWS, markers.size());
        for (int i = 0; i < shown; i++) {
            int y = LIST_TOP + i * ROW_HEIGHT;
            if (mouseY >= y - 2 && mouseY < y + ROW_HEIGHT - 4) {
                return markers.get(i);
            }
        }
        return null;
    }

    private static boolean inside(double mouseX, double mouseY,
                                  int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
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
