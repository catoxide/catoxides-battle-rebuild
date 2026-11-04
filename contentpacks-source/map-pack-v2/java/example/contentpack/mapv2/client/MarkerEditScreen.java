package example.contentpack.mapv2.client;

import example.contentpack.mapv2.MapHooks;
import example.contentpack.mapv2.MapLayer;
import example.contentpack.mapv2.MapStore;
import example.contentpack.mapv2.marker.MarkerData;
import example.contentpack.mapv2.marker.MarkerDto;
import example.contentpack.mapv2.marker.MarkerType;
import example.contentpack.mapv2.network.MarkerCreatePayload;
import example.contentpack.mapv2.network.MarkerUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 标记编辑 / 新建：名字 + 坐标 + 地图呈现方式 + 罗盘开关 + 图标（搜索 + 候选网格 + 翻页）。
 *
 * <p>编辑模式只对永久地点标记（{@link example.contentpack.mapv2.marker.MarkerType#editable()}）
 * 有意义：坐标与「名字 / 图标」都只对地点标记生效，其它类型即便被打开这里也只读；
 * 保存后走 {@link MarkerUpdatePayload}，服务端校验 owner/op。
 *
 * <p>新建模式（世界地图右键「设置路径点」）先打开这里再发 {@link MarkerCreatePayload}：
 * 这样未加载区域也能设点（不走准星 256 格限制），且 X/Z 已填、Y 留空——
 * 保存时取地图采到的地表高度，未采集就用 63。
 */
public final class MarkerEditScreen extends Screen {

    /** 新建路径点的默认图标：和旧版地图右键直接建点的图标一致。 */
    private static final ResourceLocation DEFAULT_ICON =
            ResourceLocation.withDefaultNamespace("nether_star");

    private static final int COLS = 9;
    private static final int ROWS = 4;
    private static final int CELL = 18;
    private static final int PAGE_SIZE = COLS * ROWS;
    private static final int GRID_TOP = 122;
    private static final int COORD_Y = 50;

    /** 只放行可解析成数字的中间态（"-"、"."、"-1." 都算合法输入）。 */
    private static final Predicate<String> NUMBER_FILTER =
            text -> text.isEmpty() || text.matches("-?\\d*(\\.\\d*)?");

    /** 编辑模式的目标；新建模式为 {@code null}。 */
    private final MarkerDto marker;
    private final int createWorldX;
    private final int createWorldZ;
    private final String createLayer;
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
        this.createWorldX = 0;
        this.createWorldZ = 0;
        this.createLayer = MapLayer.TOP;
        this.selectedIcon = MarkerData.iconOverride(marker) ? marker.icon() : null;
        // 没有显式写过 display 的老标记：有名字就写名字
        this.showName = !MarkerData.DISPLAY_ICON.equals(
                marker.data().getString(MarkerData.KEY_DISPLAY));
        this.compassOn = MarkerData.showsInCompass(marker);
    }

    /** 新建路径点：X/Z 已填、Y 留空，保存时按地图高度补。 */
    public MarkerEditScreen(int worldX, int worldZ, String layer) {
        super(Component.translatable("catoxidesbattlerebuild.map.marker.create"));
        this.marker = null;
        this.createWorldX = worldX;
        this.createWorldZ = worldZ;
        this.createLayer = layer;
        this.selectedIcon = null;
        this.showName = true;
        this.compassOn = true;
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        gridLeft = center - COLS * CELL / 2;
        gridTop = GRID_TOP;

        nameBox = new EditBox(this.font, center - 100, 26, 200, 18,
                Component.translatable("catoxidesbattlerebuild.map.marker.name"));
        nameBox.setMaxLength(48);
        nameBox.setValue(marker == null ? "" : marker.data().getString(MarkerData.KEY_LABEL));
        addRenderableWidget(nameBox);

        boolean creating = marker == null;
        boolean movable = creating || marker.type().editable();
        xBox = coordinateBox(center - 92, "X",
                creating ? Long.toString(createWorldX) : formatCoordinate(marker.x()), movable);
        yBox = coordinateBox(center - 24, "Y",
                creating ? "" : formatCoordinate(marker.y()), movable);
        zBox = coordinateBox(center + 44, "Z",
                creating ? Long.toString(createWorldZ) : formatCoordinate(marker.z()), movable);
        if (creating) {
            yBox.setHint(Component.translatable("catoxidesbattlerebuild.map.marker.y_auto"));
        }

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

    private EditBox coordinateBox(int x, String axis, String value, boolean editable) {
        EditBox box = new EditBox(this.font, x, COORD_Y, 56, 18, Component.literal(axis));
        box.setMaxLength(16);
        box.setFilter(NUMBER_FILTER);
        box.setValue(value);
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
            if (query.isEmpty() || matchesSearch(id, query)) {
                all.add(id);
            }
        }
        all.sort(Comparator.comparing(ResourceLocation::toString));
        filtered = all;
        if (page > maxPage()) {
            page = maxPage();
        }
    }

    /**
     * 英文 id 路径或当前语言的物品显示名，任一命中就算匹配（D82）。
     *
     * <p>显示名在过滤时现取，不在构造期建静态表：玩家切语言后旧表会冻住旧语言。
     * 方块图标的物品项显示名就是 {@code block.<ns>.<path>}，所以方块也一并覆盖。
     */
    private static boolean matchesSearch(ResourceLocation id, String query) {
        if (id.getPath().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(id))
                .getHoverName().getString().toLowerCase(Locale.ROOT).contains(query);
    }

    private int maxPage() {
        // 第 0 格是「默认（标记绑定的图标）」
        return Math.max(0, filtered.size() / PAGE_SIZE);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 留空：原版会在地图界面后面糊一层世界，这里不要
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
                graphics.renderItem(baseIconStack(), x + 1, y + 1);
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

    private ItemStack baseIconStack() {
        return marker == null
                ? new ItemStack(BuiltInRegistries.ITEM.get(DEFAULT_ICON))
                : MarkerClient.iconStack(marker);
    }

    private void save() {
        if (marker == null) {
            createMarker();
            onClose();
            return;
        }
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

    private void createMarker() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || Minecraft.getInstance().player == null) {
            return;
        }
        double x = parseCoordinate(xBox, createWorldX) + 0.5;
        double z = parseCoordinate(zBox, createWorldZ) + 0.5;
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        ResourceLocation icon = selectedIcon == null ? DEFAULT_ICON : selectedIcon;

        CompoundTag data = new CompoundTag();
        Vec3 world = resolveCreatePosition(level, x, z, blockX, blockZ, data);
        String label = nameBox == null ? "" : nameBox.getValue().trim();
        if (!label.isEmpty()) {
            data.putString(MarkerData.KEY_LABEL, label);
        }
        data.putString(MarkerData.KEY_DISPLAY,
                showName ? MarkerData.DISPLAY_NAME : MarkerData.DISPLAY_ICON);
        data.putBoolean(MarkerData.KEY_COMPASS, compassOn);
        if (selectedIcon != null) {
            data.putBoolean(MarkerData.KEY_ICON_OVERRIDE, true);
        }
        PacketDistributor.sendToServer(new MarkerCreatePayload(
                MarkerType.STICKY.name(), icon, world.x, world.y, world.z, -1, -1,
                "map:" + blockX + "," + blockZ, data, true));
    }

    /**
     * 新建点的世界坐标：Y 留空时先问扩展层（sable 甲板），再回落地图地表，
     * 未采集（未加载区域）用 63；锚点数据随解析结果写入 {@code data}。
     */
    private Vec3 resolveCreatePosition(ClientLevel level, double x, double z,
                                       int blockX, int blockZ, CompoundTag data) {
        double y;
        if (yBox != null && !yBox.getValue().trim().isEmpty()) {
            y = parseCoordinate(yBox, 63);
            return MapHooks.INSTANCE.resolveCreate(level, new Vec3(x, y, z), null, data);
        }
        Double deckY = MapHooks.INSTANCE.surfaceY(level, blockX, blockZ);
        if (deckY != null) {
            // 甲板顶面下一格：sub-level 包围盒最高到甲板方块本身
            return MapHooks.INSTANCE.resolveCreate(level,
                    new Vec3(x, deckY - 1.0, z), null, data);
        }
        int surfaceY = MapStore.sampleHeight(level.dimension().location(), createLayer, blockX, blockZ);
        y = surfaceY == Integer.MIN_VALUE ? 63.0 : surfaceY;
        return MapHooks.INSTANCE.resolveCreate(level, new Vec3(x, y, z), null, data);
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
