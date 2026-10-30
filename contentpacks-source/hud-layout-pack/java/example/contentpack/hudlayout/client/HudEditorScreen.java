package example.contentpack.hudlayout.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudAction;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudElements;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.hudlayout.layout.HudGroups;
import example.contentpack.hudlayout.layout.LayoutStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * 右键手柄打开菜单：布局系统只内置「重置位置」，其余条目由元素通过 {@link HudElement#actions()} 提供。
 */
public final class HudEditorScreen extends Screen {

    private record Target(String group, HudElement.Bounds base) {
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
    private final Set<String> reportedBoundsErrors = new HashSet<>();

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

        for (Target target : targets) {
            drawHandle(graphics, target, target == hovered, target.group().equals(draggingGroup));
        }

        graphics.drawString(font, title, 8, 8, 0xFFFFFFFF, true);
        graphics.drawString(font, Component.translatable("hudlayout.editor.hint"), 8, 20, 0xFFB0B0B0, true);

        if (menuGroup != null) {
            drawMenu(graphics, mouseX, mouseY);
        }
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
            Target target = targetAt(targets(), mouseX, mouseY);
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
        if (draggingGroup != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Target target = find(draggingGroup);
            if (target != null) {
                int grid = HudEditorConfig.snapGrid();
                int x = Math.round(((float) mouseX - grabOffsetX) / grid) * grid;
                int y = Math.round(((float) mouseY - grabOffsetY) / grid) * grid;
                HudElement.Bounds base = target.base();
                x = clamp(x, 8 - base.right(), width - 8 - base.x());
                y = clamp(y, 8 - base.bottom(), height - 8 - base.y());
                LayoutStore.set(draggingGroup, x, y);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
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
        if (keyCode == GLFW.GLFW_KEY_R && hoveredGroup != null) {
            LayoutStore.reset(hoveredGroup);
            LayoutStore.save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        LayoutStore.save();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private List<HudAction> menuItems() {
        List<HudAction> items = new ArrayList<>();
        items.add(new HudAction(Component.translatable("hudlayout.action.reset"), () -> {
            LayoutStore.reset(menuGroup);
            LayoutStore.save();
        }));
        for (HudElement element : HudElements.getAll()) {
            if (element.group().getPath().equals(menuGroup)) {
                items.addAll(element.actions());
            }
        }
        return items;
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
            String offset = Component.translatable("hudlayout.editor.offset", dx, dy).getString();
            graphics.drawString(font, offset, rect.right() - 2 - font.width(offset),
                    rect.bottom() - font.lineHeight - 1, 0xFFCCE8FF, true);
        }
    }

    private List<Target> targets() {
        List<Target> targets = new ArrayList<>();
        for (String group : HudGroups.EDITABLE) {
            HudElement.Bounds bounds = HudGroupBounds.bounds(group, width, height);
            if (bounds != null) {
                targets.add(new Target(group, bounds));
            }
        }
        for (HudElement element : HudElements.getAll()) {
            String group = element.group().getPath();
            if (containsGroup(targets, group)) {
                continue;
            }
            HudElement.Bounds bounds;
            try {
                bounds = element.bounds();
            } catch (RuntimeException e) {
                if (reportedBoundsErrors.add(element.id().toString())) {
                    LogManager.clientWarn("HudLayout", "Element '%s' failed to report its bounds: %s",
                            element.id(), e);
                }
                continue;
            }
            if (bounds != null) {
                targets.add(new Target(group, bounds));
            }
        }
        return targets;
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

    private static boolean containsGroup(List<Target> targets, String group) {
        for (Target target : targets) {
            if (target.group().equals(group)) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
