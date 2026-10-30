package example.contentpack.hudlayout.client;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudAction;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudConfigOption;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudElements;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudResize;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.hudlayout.layout.HudGroups;
import example.contentpack.hudlayout.layout.LayoutStore;
import example.contentpack.hudlayout.layout.MoveAxis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拖拽编辑屏。
 *
 * <p>背景透明：原版 HUD 在 Screen 之前渲染（{@code GameRenderer} 先 {@code gui.render}
 * 再 {@code screen.render}），所以这里看到的就是真实 HUD，手柄框直接叠在它上面；
 * 拖动写进 {@link LayoutStore} 后由 {@code wrapLayer} 每帧生效。
 *
 * <p>单人游戏暂停（{@link #isPauseScreen()}），避免编辑时被打断；多人服不受影响。
 *
 * <p>目标 = 有基准矩形的原版组 + 其他包通过 {@link HudElements} 登记的元素（按组折叠）。
 * 右键手柄打开菜单：布局系统内置「重置位置」和「移动轴」两档通用条目，其余由元素提供。
 *
 * <p>元素还能声明两件事，布局系统按声明给控件，自己不含任何元素专属逻辑：
 * <ul>
 *   <li>{@link HudElement#options()} → 「选项…」面板，读写元素所属包的配置（见 {@link HudOptionPanel}）</li>
 *   <li>{@link HudElement#resize()} → 边角拉伸手柄，拖动改写那条尺寸配置（见 {@link HudResizeHandle}）</li>
 * </ul>
 * 两者都没声明的元素就只是一块可拖动的矩形，和没有这些契约时完全一致。
 */
public final class HudEditorScreen extends Screen {

    private record Target(String group, HudElement.Bounds base,
                          @Nullable HudResize resize, List<HudConfigOption> options) {
    }

    /** 一个组累计出来的声明（同组可能有多个元素各报几条选项）。 */
    private record GroupSpec(@Nullable HudResize resize, List<HudConfigOption> options) {
    }

    private static final int MENU_ITEM_HEIGHT = 14;
    private static final int MENU_PADDING = 4;
    private static final int MENU_TEXT_PADDING = 6;

    private final Screen parent;
    private String draggingGroup;
    private String hoveredGroup;
    private int grabOffsetX;
    private int grabOffsetY;
    private String menuGroup;
    private int menuX;
    private int menuY;
    private String panelGroup;
    private HudOptionPanel panel;
    private String resizingGroup;
    private String hoveredResizeGroup;
    private boolean resizeWriteFailed;
    private PackConfig resizeConfig;
    private final Set<String> brokenElements = new HashSet<>();

    public HudEditorScreen() {
        this(null);
    }

    public HudEditorScreen(Screen parent) {
        super(Component.translatable("hudlayout.editor.title"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 留空：让真实 HUD 透出来
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        List<Target> targets = targets();
        Target hovered = targetAt(targets, mouseX, mouseY);
        hoveredGroup = hovered == null ? null : hovered.group();
        hoveredResizeGroup = groupOf(gripAt(targets, mouseX, mouseY));

        for (Target target : targets) {
            drawHandle(graphics, target, target == hovered, target.group().equals(draggingGroup));
            drawGrip(graphics, target, target.group().equals(resizingGroup));
        }

        graphics.drawString(font, title, 8, 8, 0xFFFFFFFF, true);
        graphics.drawString(font, Component.translatable("hudlayout.editor.hint"), 8, 20, 0xFFB0B0B0, true);
        graphics.drawString(font, Component.translatable("hudlayout.editor.hint.menu"), 8, 30, 0xFFB0B0B0, true);
        graphics.drawString(font, Component.translatable("hudlayout.editor.hint.keys"), 8, 40, 0xFF909090, true);

        if (panel != null && panelGroup != null) {
            panel.render(graphics, font, width, height, menuX, menuY, mouseX, mouseY);
        }
        if (menuGroup != null) {
            drawMenu(graphics, mouseX, mouseY);
        }
    }

    private static String groupOf(@Nullable Target target) {
        return target == null ? null : target.group();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (menuGroup != null) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && clickMenuItem(mouseX, mouseY)) {
                menuGroup = null;
                return true;
            }
            // 点在菜单外/非菜单键：只关闭菜单，不穿透去拖手柄
            menuGroup = null;
            return true;
        }

        if (panel != null && panelGroup != null) {
            boolean menuButton = button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
            if (menuButton && panel.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            // 面板开着时点在外面：只关面板并落盘，不穿透去拖手柄
            closePanel();
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            Target target = targetAt(targets(), mouseX, mouseY);
            if (target != null) {
                menuGroup = target.group();
                menuX = (int) Math.round(mouseX);
                menuY = (int) Math.round(mouseY);
                return true;
            }
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            List<Target> targets = targets();
            Target grip = gripAt(targets, mouseX, mouseY);
            if (grip != null) {
                resizingGroup = grip.group();
                resizeWriteFailed = false;
                return true;
            }
            Target target = targetAt(targets, mouseX, mouseY);
            if (target != null) {
                draggingGroup = target.group();
                grabOffsetX = (int) Math.round(mouseX) - LayoutStore.x(draggingGroup);
                grabOffsetY = (int) Math.round(mouseY) - LayoutStore.y(draggingGroup);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (resizingGroup != null) {
                applyResize(resizingGroup, mouseX, mouseY);
                return true;
            }
            if (panel != null && panel.mouseDragged(mouseX, mouseY)) {
                return true;
            }
        }
        if (draggingGroup != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Target target = find(draggingGroup);
            if (target != null) {
                int grid = HudEditorConfig.snapGrid();
                int x = Math.round(((float) mouseX - grabOffsetX) / grid) * grid;
                int y = Math.round(((float) mouseY - grabOffsetY) / grid) * grid;
                HudElement.Bounds base = target.base();
                x = clamp(x, 8 - base.right(), width - 8 - base.x());
                y = clamp(y, 8 - base.bottom(), height - 8 - base.y());
                // 轴约束由 LayoutStore.set 当场折算，锁住的那一轴恒为 0（= 元素的原位置）
                LayoutStore.set(draggingGroup, x, y);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (panel != null) {
            panel.mouseReleased();
        }
        if (resizingGroup != null) {
            resizingGroup = null;
            saveResizeConfig();
            return true;
        }
        if (draggingGroup != null) {
            LayoutStore.save();
            draggingGroup = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && menuGroup != null) {
            menuGroup = null;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && panel != null) {
            closePanel();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R && hoveredGroup != null) {
            LayoutStore.reset(hoveredGroup);
            LayoutStore.save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        closePanel();
        saveResizeConfig();
        LayoutStore.save();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private List<HudAction> menuItems() {
        final String group = menuGroup;
        Target target = find(group);
        List<HudAction> items = new ArrayList<>();
        items.add(new HudAction(Component.translatable("hudlayout.action.reset"), () -> {
            LayoutStore.reset(group);
            LayoutStore.save();
        }));

        MoveAxis axis = LayoutStore.axis(group);
        Component axisLabel = Component.translatable(axis.translationKey());
        items.add(new HudAction(Component.translatable("hudlayout.action.move_axis", axisLabel), () -> {
            LayoutStore.setAxis(group, axis.next());
            LayoutStore.save();
        }));

        if (target != null && !target.options().isEmpty()) {
            items.add(new HudAction(Component.translatable("hudlayout.action.options"),
                    () -> openPanel(group, target.options())));
        }

        for (HudElement element : HudElements.getAll()) {
            if (element.group().getPath().equals(group)) {
                items.addAll(element.actions());
            }
        }
        return items;
    }

    private void openPanel(String group, List<HudConfigOption> options) {
        HudOptionPanel built = HudOptionPanel.build(
                Component.translatable(HudGroups.labelKey(group)).getString(), options);
        if (built.isEmpty()) {
            // 声明了但一行都解析不出来（目标包没装等）：开一个空盒子只会让人以为坏了
            return;
        }
        panel = built;
        panelGroup = group;
    }

    private void closePanel() {
        if (panel != null) {
            panel.commit();
            panel = null;
            panelGroup = null;
        }
    }

    /** 拉伸 = 把「鼠标到固定角的距离」写成那条尺寸配置；不改偏移，元素下一帧自己的矩形就跟着变。 */
    private void applyResize(String group, double mouseX, double mouseY) {
        Target target = find(group);
        if (target == null || target.resize() == null) {
            return;
        }
        HudElement.Bounds rect = target.base().shifted(LayoutStore.x(group), LayoutStore.y(group));
        double size = HudResizeHandle.sizeFrom(rect, target.resize(), mouseX, mouseY);

        HudOptionPanel.Handle handle = HudOptionPanel.resolve(target.resize().option());
        if (handle == null) {
            return;
        }
        if (!HudOptionPanel.write(handle, size)) {
            if (!resizeWriteFailed) {
                resizeWriteFailed = true;
                LogManager.clientWarn("HudLayout", "Cannot resize group '%s' through config '%s/%s/%s'",
                        group, handle.option().packId(), handle.option().configId(), handle.option().key());
            }
            return;
        }
        resizeConfig = handle.config();
    }

    private void saveResizeConfig() {
        if (resizeConfig == null) {
            return;
        }
        try {
            resizeConfig.save();
        } catch (RuntimeException e) {
            LogManager.clientWarn("HudLayout", "Cannot save the resized config '%s': %s",
                    resizeConfig.fileName(), e.getMessage());
        }
        resizeConfig = null;
    }

    /** 手柄画在固定角的对角；没声明 {@link HudElement#resize()} 的组一个都不画。 */
    private void drawGrip(GuiGraphics graphics, Target target, boolean resizing) {
        if (target.resize() == null) {
            return;
        }
        HudElement.Bounds rect = target.base()
                .shifted(LayoutStore.x(target.group()), LayoutStore.y(target.group()));
        int[] grip = HudResizeHandle.gripRect(rect, target.resize());
        boolean hovered = target.group().equals(hoveredResizeGroup);
        int color = resizing ? 0xFFFF9900 : hovered ? 0xFF22CCFF : 0xFF66DDFF;
        graphics.fill(grip[0], grip[1], grip[0] + grip[2], grip[1] + grip[3], color);
        graphics.renderOutline(grip[0], grip[1], grip[2], grip[3], 0xFF101010);
    }

    @Nullable
    private Target gripAt(List<Target> targets, double mouseX, double mouseY) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            Target target = targets.get(i);
            if (target.resize() == null) {
                continue;
            }
            HudElement.Bounds rect = target.base()
                    .shifted(LayoutStore.x(target.group()), LayoutStore.y(target.group()));
            if (HudResizeHandle.hit(HudResizeHandle.gripRect(rect, target.resize()), mouseX, mouseY)) {
                return target;
            }
        }
        return null;
    }

    private int[] menuBounds(List<HudAction> items) {
        int width = 0;
        for (HudAction item : items) {
            width = Math.max(width, font.width(item.label()));
        }
        width += MENU_TEXT_PADDING * 2;
        int height = items.size() * MENU_ITEM_HEIGHT + MENU_PADDING * 2;
        int x = clamp(menuX, 2, Math.max(2, this.width - width - 2));
        int y = clamp(menuY, 2, Math.max(2, this.height - height - 2));
        return new int[]{x, y, width, height};
    }

    private void drawMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        List<HudAction> items = menuItems();
        int[] rect = menuBounds(items);
        graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], 0xE0101010);
        graphics.renderOutline(rect[0], rect[1], rect[2], rect[3], 0xFF66DDFF);

        for (int i = 0; i < items.size(); i++) {
            int itemY = rect[1] + MENU_PADDING + i * MENU_ITEM_HEIGHT;
            boolean hovered = mouseX >= rect[0] && mouseX < rect[0] + rect[2]
                    && mouseY >= itemY && mouseY < itemY + MENU_ITEM_HEIGHT;
            if (hovered) {
                graphics.fill(rect[0] + 1, itemY, rect[0] + rect[2] - 1, itemY + MENU_ITEM_HEIGHT, 0x5522CCFF);
            }
            graphics.drawString(font, items.get(i).label(),
                    rect[0] + MENU_TEXT_PADDING, itemY + 3, 0xFFFFFFFF, false);
        }
    }

    private boolean clickMenuItem(double mouseX, double mouseY) {
        List<HudAction> items = menuItems();
        int[] rect = menuBounds(items);
        if (mouseX < rect[0] || mouseX >= rect[0] + rect[2]
                || mouseY < rect[1] || mouseY >= rect[1] + rect[3]) {
            return false;
        }
        int index = (int) ((mouseY - rect[1] - MENU_PADDING) / MENU_ITEM_HEIGHT);
        if (index < 0 || index >= items.size()) {
            return false;
        }
        items.get(index).action().run();
        return true;
    }

    private void drawHandle(GuiGraphics graphics, Target target, boolean hovered, boolean dragging) {
        int dx = LayoutStore.x(target.group());
        int dy = LayoutStore.y(target.group());
        HudElement.Bounds rect = target.base().shifted(dx, dy);

        int fill = dragging ? 0x4400FF88 : hovered ? 0x4422CCFF : 0x331188FF;
        int outline = dragging ? 0xFF00FF88 : hovered ? 0xFF66DDFF : 0x993399FF;
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), fill);
        graphics.renderOutline(rect.x(), rect.y(), rect.width(), rect.height(), outline);

        if (HudEditorConfig.showGroupLabels()) {
            graphics.drawString(font, Component.translatable(HudGroups.labelKey(target.group())),
                    rect.x() + 2, rect.y() + 2, 0xFFFFFFFF, true);

            MutableComponent info = Component.translatable("hudlayout.editor.offset", dx, dy);
            MoveAxis axis = LayoutStore.axis(target.group());
            if (axis.isConstrained()) {
                // 锁住的那一轴恒为 0，光看数字分不清是「没拖动」还是「被约束」，所以标出约束本身
                info.append(" / ").append(Component.translatable(axis.translationKey()));
            }
            String label = info.getString();
            graphics.drawString(font, label, rect.right() - 2 - font.width(label),
                    rect.bottom() - font.lineHeight - 1, 0xFFCCE8FF, true);
        }
    }

    /**
     * 每帧重算：原版组用 {@link HudGroupBounds} 的基准矩形，其余组用元素自报的矩形。
     *
     * <p>同组多个元素会折叠成一块手柄（它们共用同一份偏移，分开画会拖一个跳一片），
     * 各自的 {@code options()} 拼进同一面板；{@code resize()} 只取第一个声明的——
     * 一块矩形只有四个角，两个拉伸声明没法同时成立。
     */
    private List<Target> targets() {
        Map<String, HudElement.Bounds> elementBounds = new LinkedHashMap<>();
        Map<String, GroupSpec> groupSpecs = new LinkedHashMap<>();
        for (HudElement element : HudElements.getAll()) {
            String group = element.group().getPath();
            try {
                HudElement.Bounds bounds = element.bounds();
                if (bounds != null) {
                    elementBounds.putIfAbsent(group, bounds);
                }
                groupSpecs.put(group, declared(groupSpecs.get(group), element));
            } catch (RuntimeException e) {
                if (brokenElements.add(group + "/" + element.id())) {
                    LogManager.clientWarn("HudLayout", "Element '%s' failed to describe itself: %s", element.id(), e);
                }
            }
        }

        List<Target> targets = new ArrayList<>();
        for (String group : HudGroups.EDITABLE) {
            HudElement.Bounds vanilla = HudGroupBounds.bounds(group, width, height);
            if (vanilla == null) {
                continue;
            }
            // 原版基准矩形优先，元素自报的同组矩形不再单独成块；但它的声明仍然挂在这个组上
            elementBounds.remove(group);
            targets.add(targetFor(group, vanilla, groupSpecs));
        }
        elementBounds.forEach((group, bounds) -> targets.add(targetFor(group, bounds, groupSpecs)));
        return targets;
    }

    private static Target targetFor(String group, HudElement.Bounds bounds, Map<String, GroupSpec> groupSpecs) {
        GroupSpec spec = groupSpecs.get(group);
        return new Target(group, bounds,
                spec == null ? null : spec.resize(),
                spec == null ? List.of() : spec.options());
    }

    private static GroupSpec declared(@Nullable GroupSpec existing, HudElement element) {
        HudResize resize = existing == null || existing.resize() == null ? element.resize() : existing.resize();
        List<HudConfigOption> options = new ArrayList<>();
        if (existing != null) {
            options.addAll(existing.options());
        }
        for (HudConfigOption option : element.options()) {
            if (!options.contains(option)) {
                options.add(option);
            }
        }
        return new GroupSpec(resize, List.copyOf(options));
    }

    private Target targetAt(List<Target> targets, double mouseX, double mouseY) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            Target target = targets.get(i);
            HudElement.Bounds rect = target.base()
                    .shifted(LayoutStore.x(target.group()), LayoutStore.y(target.group()));
            if (rect.contains(mouseX, mouseY)) {
                return target;
            }
        }
        return null;
    }

    private Target find(String group) {
        for (Target target : targets()) {
            if (target.group().equals(group)) {
                return target;
            }
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
