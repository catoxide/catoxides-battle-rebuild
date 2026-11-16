package example.contentpack.hudlayout.ui;

import com.catoxide.catoxidesbattlerebuild.core.ui.IScreenPanelAccess;
import com.catoxide.catoxidesbattlerebuild.core.ui.IUiEditTarget;
import com.catoxide.catoxidesbattlerebuild.core.ui.ScreenPanels;
import com.catoxide.catoxidesbattlerebuild.core.ui.UiEditTargets;
import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;
import example.contentpack.hudlayout.client.HudEditorConfig;
import example.contentpack.hudlayout.client.HudLayoutClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器屏上的拖拽编辑模式：开着容器按 F8 进入，拖面板与屏内元素，松手即落盘。
 *
 * <p>和 HUD 编辑屏同一套手感（吸附网格、R 复位、ESC 保存退出），区别是不另开屏：
 * 编辑就是叠在真实容器屏上的一层遮罩，所见即所得。进入后吞掉该屏的鼠标与键盘事件，
 * 否则一拖就会把物品拿起来。F8 的键位沿用 HUD 编辑屏那一条，只是分「有没有容器屏」两种场合。
 *
 * <p>可拖的东西有两类：容器面板本身（{@link ScreenPanels} 登记、{@link UiLayoutClient} 跟踪的屏，
 * 存 {@code panels} 段）和屏内元素（{@link UiEditTargets} 登记，存 {@code elements} 段）。
 * 元素画在面板之上，所以命中测试先元素后面板。
 *
 * <p>只能改偏移和效果列锚定；尺寸、布局变体归面板所属的包（契约 §6.6、§6.7）。
 */
public final class UiEditMode {

    private static final int DIM = 0x66000000;
    private static final int OUTLINE = 0xFF22CCFF;
    private static final int OUTLINE_DRAGGING = 0xFF00FF88;
    private static final int EFFECTS_OUTLINE = 0x99FFAA00;
    private static final int ELEMENT_FILL = 0x1A7FE7B0;
    private static final int ELEMENT_OUTLINE = 0xFF7FE7B0;
    private static final int ELEMENT_OUTLINE_DRAGGING = 0xFFFFE100;

    /** 原版的 32px 空间门槛就是效果列的名义宽度；这里只是给锚定位置画个框。 */
    private static final int EFFECTS_WIDTH = 32;

    /**
     * 吸附半径：被拖框的左/中/右（上/中/下）三条线离候选线不超过它就贴上去。
     * 候选线按优先级是「容器面板 → 同屏其他元素 → 屏幕 1/4 线」（{@link #collectLines}）。
     */
    private static final int GUIDE_SNAP = 2;
    private static final int GUIDE_COLOR = 0x2EFFFFFF;
    private static final int GUIDE_COLOR_ACTIVE = 0xFF00E5FF;
    private static final int NO_GUIDE = Integer.MIN_VALUE;

    private static @Nullable AbstractContainerScreen<?> editing;
    /** 这一屏没有登记面板时为 {@code null}：只有元素可拖。 */
    private static @Nullable ResourceLocation panelId;
    private static @Nullable UiDrag panelDrag;
    private static @Nullable IUiEditTarget elementDragging;
    private static @Nullable UiDrag elementDrag;
    private static int guideX = NO_GUIDE;
    private static int guideY = NO_GUIDE;
    private static boolean dirty;

    private UiEditMode() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(UiEditMode::onKeyPressed);
        NeoForge.EVENT_BUS.addListener(UiEditMode::onCharacterTyped);
        NeoForge.EVENT_BUS.addListener(UiEditMode::onMousePressed);
        NeoForge.EVENT_BUS.addListener(UiEditMode::onMouseDragged);
        NeoForge.EVENT_BUS.addListener(UiEditMode::onMouseReleased);
        NeoForge.EVENT_BUS.addListener(UiEditMode::onMouseScrolled);
        NeoForge.EVENT_BUS.addListener(UiEditMode::onRenderPost);
        NeoForge.EVENT_BUS.addListener(UiEditMode::onScreenClosing);
    }

    private static boolean isEditing(@Nullable Screen screen) {
        return screen != null && screen == editing;
    }

    private static void enter(AbstractContainerScreen<?> container) {
        ResourceLocation id = ScreenPanels.idFor(container);
        boolean panel = id != null && UiLayoutClient.isTracked(container);
        List<IUiEditTarget> targets = UiEditTargets.present(container);
        if (!panel && targets.isEmpty()) {
            return;
        }
        UiLayoutStore.reloadIfChanged();
        // 文件可能在这次打开之前被手改过：先按新值摆好元素，再进编辑
        UiLayoutClient.applyElementOffsets(container);
        editing = container;
        panelId = panel ? id : null;
        panelDrag = null;
        elementDragging = null;
        elementDrag = null;
        clearGuides();
        dirty = false;
    }

    private static void exit() {
        saveIfDirty();
        editing = null;
        panelId = null;
        panelDrag = null;
        elementDragging = null;
        elementDrag = null;
        clearGuides();
        dirty = false;
    }

    private static void clearGuides() {
        guideX = NO_GUIDE;
        guideY = NO_GUIDE;
    }

    private static void saveIfDirty() {
        if (!dirty) {
            return;
        }
        UiLayoutStore.save();
        dirty = false;
    }

    private static void reset() {
        if (editing == null) {
            return;
        }
        boolean changed = false;
        if (panelId != null) {
            // 只复位位置：效果列锚定不是位置，别一起丢掉
            UiLayoutStore.set(panelId, new UiPanelLayout(0, 0, UiLayoutStore.get(panelId).effects()));
            changed = true;
        }
        for (IUiEditTarget target : UiEditTargets.present(editing)) {
            UiLayoutStore.setElement(target.id(), UiPanelLayout.DEFAULT);
            target.setOffset(0, 0);
            changed = true;
        }
        if (changed) {
            UiLayoutStore.save();
        }
        dirty = false;
    }

    private static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        if (HudLayoutClient.OPEN_EDITOR.matches(event.getKeyCode(), event.getScanCode())) {
            event.setCanceled(true);
            if (isEditing(container)) {
                exit();
            } else {
                enter(container);
            }
            return;
        }
        if (!isEditing(container)) {
            return;
        }

        // 编辑时吞掉所有按键：数字键会在悬停槽位上换物品，字母键会打进铁砧的名字框
        event.setCanceled(true);
        if (event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) {
            exit();
        } else if (event.getKeyCode() == GLFW.GLFW_KEY_R) {
            reset();
        }
    }

    private static void onCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (isEditing(event.getScreen())) {
            event.setCanceled(true);
        }
    }

    private static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        if (!isEditing(container)) {
            // 键位也可能被改绑到鼠标键；键盘事件里拿不到鼠标键，只能在这里接
            if (HudLayoutClient.OPEN_EDITOR.matchesMouse(event.getButton())) {
                event.setCanceled(true);
                enter(container);
            }
            return;
        }

        event.setCanceled(true);
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (panelId != null) {
                toggleEffectsAnchor(container, (IScreenPanelAccess) container,
                        event.getMouseX(), event.getMouseY());
            }
            return;
        }
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }

        IUiEditTarget hit = hitElement(container, event.getMouseX(), event.getMouseY());
        if (hit != null) {
            elementDragging = hit;
            elementDrag = dragFrom(hit, container, event.getMouseX(), event.getMouseY());
            return;
        }
        if (panelId == null) {
            return;
        }
        IScreenPanelAccess access = (IScreenPanelAccess) container;
        panelDrag = new UiDrag(UiLayoutClient.baseLeft(container), UiLayoutClient.baseTop(container),
                access.catoxides$panelLeft(), access.catoxides$panelTop(),
                event.getMouseX(), event.getMouseY());
    }

    /** 拖动取值只要「基准 + 当前生效位置 + 按下时的鼠标」，和面板那条路径同一个 {@link UiDrag}。 */
    private static UiDrag dragFrom(IUiEditTarget target, AbstractContainerScreen<?> container,
                                   double mouseX, double mouseY) {
        UiRect base = target.baseBounds(container);
        return new UiDrag(base.x(), base.y(), base.x() + target.offsetX(), base.y() + target.offsetY(),
                mouseX, mouseY);
    }

    /** 后画的在更上层：元素画在面板之上，所以命中测试先元素、后由调用方落到面板。 */
    private static @Nullable IUiEditTarget hitElement(AbstractContainerScreen<?> container,
                                                      double mouseX, double mouseY) {
        List<IUiEditTarget> targets = UiEditTargets.present(container);
        for (int i = targets.size() - 1; i >= 0; i--) {
            IUiEditTarget target = targets.get(i);
            UiRect bounds = target.baseBounds(container).offset(target.offsetX(), target.offsetY());
            if (bounds.contains(mouseX, mouseY)) {
                return target;
            }
        }
        return null;
    }

    private static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!isEditing(event.getScreen()) || (panelDrag == null && elementDrag == null)) {
            return;
        }
        event.setCanceled(true);
        if (event.getMouseButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        AbstractContainerScreen<?> container = (AbstractContainerScreen<?>) event.getScreen();
        if (elementDrag != null) {
            dragElement(container, event.getMouseX(), event.getMouseY());
            return;
        }
        dragPanel(container, event.getMouseX(), event.getMouseY());
    }

    private static void dragElement(AbstractContainerScreen<?> container, double mouseX, double mouseY) {
        IUiEditTarget target = elementDragging;
        if (target == null || elementDrag == null) {
            return;
        }
        UiRect base = target.baseBounds(container);
        UiPanelLayout loose = elementDrag.apply(mouseX, mouseY, HudEditorConfig.snapGrid(),
                container.width, container.height, base.width(), base.height(), UiPanelLayout.DEFAULT);
        List<Integer> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        collectLines(container, target, xs, ys);
        int left = base.x() + loose.x();
        int top = base.y() + loose.y();
        int[] snappedX = UiSnap.axis(left, base.width(), xs, GUIDE_SNAP);
        int[] snappedY = UiSnap.axis(top, base.height(), ys, GUIDE_SNAP);
        guideX = snappedX == null ? NO_GUIDE : snappedX[1];
        guideY = snappedY == null ? NO_GUIDE : snappedY[1];
        if (snappedX != null) {
            left = snappedX[0];
        }
        if (snappedY != null) {
            top = snappedY[0];
        }
        // 吸附是往邻近线多挪一两像素，可能越过夹边；越过就放弃这条吸附
        int clampedLeft = UiGeometry.clampPanel(left, container.width, base.width());
        int clampedTop = UiGeometry.clampPanel(top, container.height, base.height());
        if (clampedLeft != left) {
            left = clampedLeft;
            guideX = NO_GUIDE;
        }
        if (clampedTop != top) {
            top = clampedTop;
            guideY = NO_GUIDE;
        }
        if (left - base.x() == target.offsetX() && top - base.y() == target.offsetY()) {
            return;
        }
        UiLayoutStore.setElement(target.id(), new UiPanelLayout(left - base.x(), top - base.y(), null));
        target.setOffset(left - base.x(), top - base.y());
        dirty = true;
    }

    private static void dragPanel(AbstractContainerScreen<?> container, double mouseX, double mouseY) {
        if (panelId == null || panelDrag == null) {
            return;
        }
        UiPanelLayout current = UiLayoutStore.get(panelId);
        UiPanelLayout loose = panelDrag.apply(mouseX, mouseY, HudEditorConfig.snapGrid(),
                container.width, container.height, container.getXSize(), container.getYSize(), current);
        // 元素跟着面板走，拿它们的线当参照等于自己跟自己比；拖面板只吸屏幕 1/4 线
        List<Integer> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        collectLines(container, null, xs, ys);
        int baseLeft = UiLayoutClient.baseLeft(container);
        int baseTop = UiLayoutClient.baseTop(container);
        int left = baseLeft + loose.x();
        int top = baseTop + loose.y();
        int[] snappedX = UiSnap.axis(left, container.getXSize(), xs, GUIDE_SNAP);
        int[] snappedY = UiSnap.axis(top, container.getYSize(), ys, GUIDE_SNAP);
        guideX = snappedX == null ? NO_GUIDE : snappedX[1];
        guideY = snappedY == null ? NO_GUIDE : snappedY[1];
        if (snappedX != null) {
            left = snappedX[0];
        }
        if (snappedY != null) {
            top = snappedY[0];
        }
        int clampedLeft = UiGeometry.clampPanel(left, container.width, container.getXSize());
        int clampedTop = UiGeometry.clampPanel(top, container.height, container.getYSize());
        if (clampedLeft != left) {
            left = clampedLeft;
            guideX = NO_GUIDE;
        }
        if (clampedTop != top) {
            top = clampedTop;
            guideY = NO_GUIDE;
        }
        UiPanelLayout moved = new UiPanelLayout(left - baseLeft, top - baseTop, loose.effects());
        if (!moved.equals(current)) {
            UiLayoutStore.set(panelId, moved);
            dirty = true;
        }
    }

    private static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!isEditing(event.getScreen())) {
            return;
        }
        event.setCanceled(true);
        boolean moved = panelDrag != null || elementDrag != null;
        panelDrag = null;
        elementDragging = null;
        elementDrag = null;
        clearGuides();
        if (moved) {
            saveIfDirty();
        }
    }

    /** 滚轮在配方书屏里会翻页；编辑时别让它跟着动。 */
    private static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (isEditing(event.getScreen())) {
            event.setCanceled(true);
        }
    }

    private static void onScreenClosing(ScreenEvent.Closing event) {
        if (isEditing(event.getScreen())) {
            exit();
        }
    }

    private static void onRenderPost(ScreenEvent.Render.Post event) {
        if (!isEditing(event.getScreen())) {
            return;
        }
        AbstractContainerScreen<?> container = (AbstractContainerScreen<?>) event.getScreen();
        IScreenPanelAccess access = (IScreenPanelAccess) container;
        GuiGraphics graphics = event.getGuiGraphics();
        Minecraft minecraft = Minecraft.getInstance();

        int left = access.catoxides$panelLeft();
        int top = access.catoxides$panelTop();
        int screenWidth = container.width;
        int screenHeight = container.height;
        drawDim(graphics, screenWidth, screenHeight, left, top, container.getXSize(), container.getYSize());

        int[] effects = panelId == null ? null : effectsRect(container, access);
        if (panelId != null) {
            graphics.renderOutline(left, top, container.getXSize(), container.getYSize(),
                    panelDrag != null ? OUTLINE_DRAGGING : OUTLINE);
            if (effects != null) {
                graphics.renderOutline(effects[0], effects[1], effects[2], effects[3], EFFECTS_OUTLINE);
            }

            UiPanelLayout layout = UiLayoutStore.get(panelId);
            String title = Component.translatable("hudlayout.ui_editor.title", layout.x() + "," + layout.y())
                    .getString();
            int titleY = top >= 12 ? top - 10 : Math.min(screenHeight - 10, top + container.getYSize() + 2);
            graphics.drawString(minecraft.font, title, Math.max(2, left), titleY, 0xFFCCE8FF, true);
        }

        drawElements(graphics, minecraft, container);
        drawScreenGuides(graphics, screenWidth, screenHeight);

        String hint = Component.translatable("hudlayout.ui_editor.hint", HudEditorConfig.snapGrid()).getString();
        graphics.drawString(minecraft.font, hint,
                Math.max(2, (screenWidth - minecraft.font.width(hint)) / 2), screenHeight - 12, 0xFFB0B0B0, true);
        if (effects != null) {
            String extra = Component.translatable("hudlayout.ui_editor.hint.effects").getString();
            graphics.drawString(minecraft.font, extra,
                    Math.max(2, (screenWidth - minecraft.font.width(extra)) / 2),
                    screenHeight - 22, 0xFFB0B0B0, true);
        }
    }

    /** 屏内元素：绿框 + 当前偏移；被拖的那个转黄。 */
    private static void drawElements(GuiGraphics graphics, Minecraft minecraft,
                                     AbstractContainerScreen<?> container) {
        for (IUiEditTarget target : UiEditTargets.present(container)) {
            UiRect bounds = target.baseBounds(container).offset(target.offsetX(), target.offsetY());
            int outline = target == elementDragging ? ELEMENT_OUTLINE_DRAGGING : ELEMENT_OUTLINE;
            graphics.fill(bounds.x(), bounds.y(),
                    bounds.x() + bounds.width(), bounds.y() + bounds.height(), ELEMENT_FILL);
            graphics.renderOutline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), outline);
            String label = target.id().getPath() + " " + target.offsetX() + "," + target.offsetY();
            graphics.drawString(minecraft.font, label,
                    Math.max(2, bounds.x() + 2), Math.max(2, bounds.y() + 2), 0xFFFFFFFF, true);
        }
    }

    /**
     * 吸附候选线，按优先级：容器面板 → 同屏其他元素 → 屏幕 1/4 线。同距离取靠前的。
     *
     * @param dragged 正在拖的元素；{@code null} 表示拖的是面板本身——元素跟着面板走，
     *                它们的线不是固定参照，所以那条路径只留屏幕线
     */
    private static void collectLines(AbstractContainerScreen<?> container, @Nullable IUiEditTarget dragged,
                                     List<Integer> xs, List<Integer> ys) {
        if (dragged != null) {
            addLines(xs, ys, new UiRect(container.getGuiLeft(), container.getGuiTop(),
                    container.getXSize(), container.getYSize()));
            for (IUiEditTarget other : UiEditTargets.present(container)) {
                if (other != dragged) {
                    addLines(xs, ys, other.baseBounds(container).offset(other.offsetX(), other.offsetY()));
                }
            }
        }
        for (int i = 1; i <= 3; i++) {
            xs.add(container.width * i / 4);
            ys.add(container.height * i / 4);
        }
    }

    private static void addLines(List<Integer> xs, List<Integer> ys, UiRect rect) {
        xs.add(rect.x());
        xs.add(rect.x() + rect.width() / 2);
        xs.add(rect.x() + rect.width());
        ys.add(rect.y());
        ys.add(rect.y() + rect.height() / 2);
        ys.add(rect.y() + rect.height());
    }

    /** 常驻 4×4 参考线；吸附命中时在命中的坐标上叠一条亮线（不一定落在 1/4 上）。 */
    private static void drawScreenGuides(GuiGraphics graphics, int screenWidth, int screenHeight) {
        for (int i = 1; i <= 3; i++) {
            int x = screenWidth * i / 4;
            int y = screenHeight * i / 4;
            graphics.fill(x, 0, x + 1, screenHeight, GUIDE_COLOR);
            graphics.fill(0, y, screenWidth, y + 1, GUIDE_COLOR);
        }
        if (guideX != NO_GUIDE) {
            graphics.fill(guideX, 0, guideX + 1, screenHeight, GUIDE_COLOR_ACTIVE);
        }
        if (guideY != NO_GUIDE) {
            graphics.fill(0, guideY, screenWidth, guideY + 1, GUIDE_COLOR_ACTIVE);
        }
    }

    /** 面板外面压暗，面板本身保持原样——一眼看出哪块在动。 */
    private static void drawDim(GuiGraphics graphics, int screenWidth, int screenHeight,
                                int left, int top, int width, int height) {
        int l = clamp(left, 0, screenWidth);
        int r = clamp(left + width, 0, screenWidth);
        int t = clamp(top, 0, screenHeight);
        int b = clamp(top + height, 0, screenHeight);
        graphics.fill(0, 0, screenWidth, t, DIM);
        graphics.fill(0, b, screenWidth, screenHeight, DIM);
        graphics.fill(0, t, l, b, DIM);
        graphics.fill(r, t, screenWidth, b, DIM);
    }

    /**
     * 效果列「会落在哪」的框；没配锚定、屏不支持效果列、玩家没有效果时都不画。
     *
     * @return {@code null} 或 {@code {x, y, w, h}}
     */
    private static int[] effectsRect(AbstractContainerScreen<?> container, IScreenPanelAccess access) {
        if (panelId == null || !(container instanceof EffectRenderingInventoryScreen<?>)) {
            return null;
        }
        UiEffectsAnchor effects = UiLayoutStore.get(panelId).effects();
        if (effects == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int count = minecraft.player == null ? 0 : minecraft.player.getActiveEffects().size();
        if (count < 1) {
            return null;
        }
        int panelTop = access.catoxides$panelTop();
        int top = panelTop + effects.shiftY(panelTop, container.getYSize(), count);
        return new int[]{effects.horizontalX(access.catoxides$panelLeft(), container.getXSize()), top,
                EFFECTS_WIDTH, UiEffectsAnchor.blockHeight(count)};
    }

    /** 右键效果列框：右上 ↔ 右下。其余参数（间距、最大长度）原样保留。 */
    private static boolean toggleEffectsAnchor(AbstractContainerScreen<?> container, IScreenPanelAccess access,
                                               double mouseX, double mouseY) {
        int[] rect = effectsRect(container, access);
        if (rect == null || mouseX < rect[0] || mouseX >= rect[0] + rect[2]
                || mouseY < rect[1] || mouseY >= rect[1] + rect[3]) {
            return false;
        }
        UiPanelLayout layout = UiLayoutStore.get(panelId);
        UiEffectsAnchor effects = layout.effects();
        if (effects == null) {
            return false;
        }
        UiEffectsAnchor.Anchor next = effects.anchor() == UiEffectsAnchor.Anchor.TOP_RIGHT
                ? UiEffectsAnchor.Anchor.BOTTOM_RIGHT
                : UiEffectsAnchor.Anchor.TOP_RIGHT;
        UiLayoutStore.set(panelId, new UiPanelLayout(layout.x(), layout.y(),
                new UiEffectsAnchor(next, effects.gapX(), effects.gapY(), effects.maxLength())));
        UiLayoutStore.save();
        dirty = false;
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
