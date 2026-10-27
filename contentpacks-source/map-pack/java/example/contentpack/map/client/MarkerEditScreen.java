package example.contentpack.map.client;

import example.contentpack.map.marker.MarkerData;
import example.contentpack.map.marker.MarkerDto;
import example.contentpack.map.network.MarkerUpdatePayload;
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

/**
 * 标记编辑：名字 + 图标（搜索 + 候选网格 + 翻页）。
 *
 * <p>图标候选是全部已注册物品；保存后走 {@link MarkerUpdatePayload}，服务端校验 owner/op。
 */
public final class MarkerEditScreen extends Screen {

    private static final int COLS = 9;
    private static final int ROWS = 5;
    private static final int CELL = 18;
    private static final int PAGE_SIZE = COLS * ROWS;

    private final MarkerDto marker;
    private EditBox nameBox;
    private EditBox searchBox;
    /** null = 默认表现（地点标记=三角形；物品/实体=本体图标/头像）。 */
    private ResourceLocation selectedIcon;
    private int page;
    private List<ResourceLocation> filtered = List.of();
    private int gridLeft;
    private int gridTop;

    public MarkerEditScreen(MarkerDto marker) {
        super(Component.translatable("catoxidesbattlerebuild.map.marker.edit"));
        this.marker = marker;
        this.selectedIcon = MarkerData.iconOverride(marker) ? marker.icon() : null;
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        gridLeft = center - COLS * CELL / 2;
        gridTop = 84;

        nameBox = new EditBox(this.font, center - 100, 30, 200, 20,
                Component.translatable("catoxidesbattlerebuild.map.marker.name"));
        nameBox.setMaxLength(48);
        nameBox.setValue(marker.data().getString("label"));
        addRenderableWidget(nameBox);

        searchBox = new EditBox(this.font, center - 100, 56, 200, 20,
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
        // 第 0 格是「默认三角形」
        return Math.max(0, filtered.size() / PAGE_SIZE);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xD0101010);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
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
                // 默认表现：白色向下三角形（TODO: 颜色做成可配置/可重着色）
                MarkerHud.drawTriangle(graphics, x + CELL / 2, y + CELL - 3, 5, 0xFFFFFFFF);
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
                gridTop + ROWS * CELL + 4, 0xFFAAAAAA);
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
        PacketDistributor.sendToServer(new MarkerUpdatePayload(
                marker.id(), resetIcon ? marker.icon() : selectedIcon,
                nameBox == null ? "" : nameBox.getValue(), resetIcon));
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
