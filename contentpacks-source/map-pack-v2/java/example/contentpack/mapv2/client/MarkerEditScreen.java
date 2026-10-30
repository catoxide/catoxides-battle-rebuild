package example.contentpack.mapv2.client;

import example.contentpack.mapv2.marker.MarkerData;
import example.contentpack.mapv2.marker.MarkerDto;
import example.contentpack.mapv2.network.MarkerUpdatePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 标记编辑：名字 + 坐标 + 地图呈现方式 + 罗盘开关 + 图标（搜索 + 候选网格 + 翻页）。
 *
 * <p>只对永久地点标记（{@link example.contentpack.mapv2.marker.MarkerType#editable()}）有意义：
 * 坐标与「名字 / 图标」都只对地点标记生效，其它类型即便被打开这里也只读；
 * 保存后走 {@link MarkerUpdatePayload}，服务端校验 owner/op。
 */
public final class MarkerEditScreen extends Screen {

    private static final int COLS = 9;
    private static final int ROWS = 4;
    private static final int CELL = 18;
    private static final int PAGE_SIZE = COLS * ROWS;
    private static final int GRID_TOP = 122;
    private static final int COORD_Y = 50;

    /** 只放行可解析成数字的中间态（"-"、"."、"-1." 都算合法输入）。 */
    private static final Predicate<String> NUMBER_FILTER =
            text -> text.isEmpty() || text.matches("-?\\d*(\\.\\d*)?");

    private final MarkerDto marker;
    private EditBox nameBox;
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private EditBox searchBox;
    /** null = 默认表现（标记自己绑定的图标；物品/实体标记就是本体图标/头像）。 */
    private ResourceLocation selectedIcon;
    private boolean showName;
    private boolean compassOn;
    private int page;
    private List<ResourceLocation> filtered = List.of();
    private int gridLeft;
    private int gridTop;

    public MarkerEditScreen(MarkerDto marker) {
        super(Component.translatable("catoxidesbattlerebuild.map.marker.edit"));
        this.marker = marker;
        this.selectedIcon = MarkerData.iconOverride(marker) ? marker.icon() : null;
        // 没有显式写过 display 的老标记：有名字就写名字
        this.showName = !MarkerData.DISPLAY_ICON.equals(
                marker.data().getString(MarkerData.KEY_DISPLAY));
        this.compassOn = MarkerData.showsInCompass(marker);
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        gridLeft = center - COLS * CELL / 2;
        gridTop = GRID_TOP;

        nameBox = new EditBox(this.font, center - 100, 26, 200, 18,
                Component.translatable("catoxidesbattlerebuild.map.marker.name"));
        nameBox.setMaxLength(48);
        nameBox.setValue(marker.data().getString(MarkerData.KEY_LABEL));
        addRenderableWidget(nameBox);

        boolean movable = marker.type().editable();
        xBox = coordinateBox(center - 92, "X", marker.x(), movable);
        yBox = coordinateBox(center - 24, "Y", marker.y(), movable);
        zBox = coordinateBox(center + 44, "Z", marker.z(), movable);

        Button displayButton = addRenderableWidget(Button.builder(displayText(), button -> {
            showName = !showName;
            button.setMessage(displayText());
        }).bounds(center - 100, 74, 96, 18).build());
        displayButton.active = movable;

        addRenderableWidget(Button.builder(compassText(), button -> {
            compassOn = !compassOn;
            button.setMessage(compassText());
        }).bounds(center + 4, 74, 96, 18).build());

        searchBox = new EditBox(this.font, center - 100, 98, 200, 18,
                Component.translatable("catoxidesbattlerebuild.map.marker.search"));
        searchBox.setMaxLength(32);
        searchBox.setResponder(value -> {
            page = 0;
            refresh();
        });
        addRenderableWidget(searchBox);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> save())
                .bounds(center - 100, this.height - 30, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(center + 4, this.height - 30, 96, 20).build());

        addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            page = Math.max(0, page - 1);
        }).bounds(gridLeft - 24, gridTop + ROWS * CELL / 2 - 10, 18, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> {
            page = Math.min(maxPage(), page + 1);
        }).bounds(gridLeft + COLS * CELL + 6, gridTop + ROWS * CELL / 2 - 10, 18, 20).build());

        refresh();
    }

    private EditBox coordinateBox(int x, String axis, double value, boolean editable) {
        EditBox box = new EditBox(this.font, x, COORD_Y, 56, 18, Component.literal(axis));
        box.setMaxLength(16);
        box.setFilter(NUMBER_FILTER);
        box.setValue(formatCoordinate(value));
        box.setEditable(editable);
        addRenderableWidget(box);
        return box;
    }

    private Component displayText() {
        return Component.translatable(showName
                ? "catoxidesbattlerebuild.map.marker.display.name"
                : "catoxidesbattlerebuild.map.marker.display.icon");
    }

    private Component compassText() {
        return Component.translatable(compassOn
                ? "catoxidesbattlerebuild.map.marker.compass.on"
                : "catoxidesbattlerebuild.map.marker.compass.off");
    }

    private void refresh() {
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        List<ResourceLocation> all = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (query.isEmpty() || id.getPath().contains(query)) {
                all.add(id);
            }
        }
        all.sort(Comparator.comparing(ResourceLocation::toString));
        filtered = all;
        if (page > maxPage()) {
            page = maxPage();
        }
    }

    private int maxPage() {
        // 第 0 格是「默认（标记绑定的图标）」
        return Math.max(0, filtered.size() / PAGE_SIZE);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xD0101010);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        int center = this.width / 2;
        graphics.drawString(this.font, "X", center - 100, COORD_Y + 5, 0xFFAAAAAA);
        graphics.drawString(this.font, "Y", center - 32, COORD_Y + 5, 0xFFAAAAAA);
        graphics.drawString(this.font, "Z", center + 36, COORD_Y + 5, 0xFFAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);

        int start = page * PAGE_SIZE;
        int total = filtered.size() + 1;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = start + i;
            if (index >= total) {
                break;
            }
            int x = gridLeft + (i % COLS) * CELL;
            int y = gridTop + (i / COLS) * CELL;
            boolean selected;
            if (index == 0) {
                // 默认表现：标记自己绑定的图标（永久标记必须绑真实图标，不再用三角形）
                graphics.renderItem(MarkerClient.iconStack(marker), x + 1, y + 1);
                selected = selectedIcon == null;
            } else {
                ResourceLocation id = filtered.get(index - 1);
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
                graphics.renderItem(stack, x + 1, y + 1);
                selected = id.equals(selectedIcon);
            }
            if (selected) {
                graphics.fill(x, y, x + CELL, y + 1, 0xFFFFFF00);
                graphics.fill(x, y + CELL - 1, x + CELL, y + CELL, 0xFFFFFF00);
                graphics.fill(x, y, x + 1, y + CELL, 0xFFFFFF00);
                graphics.fill(x + CELL - 1, y, x + CELL, y + CELL, 0xFFFFFF00);
            }
        }
        String pageText = (page + 1) + "/" + (maxPage() + 1);
        graphics.drawCenteredString(this.font, pageText, this.width / 2,
                gridTop + ROWS * CELL + 6, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int start = page * PAGE_SIZE;
            int total = filtered.size() + 1;
            for (int i = 0; i < PAGE_SIZE; i++) {
                int index = start + i;
                if (index >= total) {
                    break;
                }
                int x = gridLeft + (i % COLS) * CELL;
                int y = gridTop + (i / COLS) * CELL;
                if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                    selectedIcon = index == 0 ? null : filtered.get(index - 1);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void save() {
        boolean resetIcon = selectedIcon == null;
        boolean movable = marker.type().editable();
        PacketDistributor.sendToServer(new MarkerUpdatePayload(
                marker.id(), resetIcon ? marker.icon() : selectedIcon,
                nameBox == null ? "" : nameBox.getValue().trim(), resetIcon,
                movable ? parseCoordinate(xBox, marker.x()) : marker.x(),
                movable ? parseCoordinate(yBox, marker.y()) : marker.y(),
                movable ? parseCoordinate(zBox, marker.z()) : marker.z(),
                movable && showName, compassOn));
        onClose();
    }

    private static double parseCoordinate(EditBox box, double fallback) {
        if (box == null) {
            return fallback;
        }
        String text = box.getValue().trim();
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String formatCoordinate(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
