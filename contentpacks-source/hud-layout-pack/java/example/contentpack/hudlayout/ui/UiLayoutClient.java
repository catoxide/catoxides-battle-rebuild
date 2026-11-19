package example.contentpack.hudlayout.ui;

import com.catoxide.catoxidesbattlerebuild.core.ui.IScreenPanelAccess;
import com.catoxide.catoxidesbattlerebuild.core.ui.IUiEditTarget;
import com.catoxide.catoxidesbattlerebuild.core.ui.ScreenCuts;
import com.catoxide.catoxidesbattlerebuild.core.ui.ScreenLayoutHooks;
import com.catoxide.catoxidesbattlerebuild.core.ui.ScreenPanels;
import com.catoxide.catoxidesbattlerebuild.core.ui.UiEditTargets;
import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 容器面板布局的客户端接线（A7：全部显式挂事件，不用 {@code @EventBusSubscriber}）。
 *
 * <p>不变量（契约 §4）：{@code leftPos = base + offset}。原版 {@code init}、配方书开合、
 * 其他 mod 的 mixin 都会写 {@code leftPos}，所以每帧先比较「我们上次写的值」，
 * 对不上就说明有人改了基准，直接采信当前值——不做累加，也就不会漂移。
 *
 * <p>原版还有一些控件的位置在 {@code init} 里就定死了（配方书开关按钮）：这里在
 * {@code Init.Post} 记下它与面板的相对位置，面板一动就跟着摆。
 */
public final class UiLayoutClient {

    private static final Map<AbstractContainerScreen<?>, PanelRun> RUNNING = new WeakHashMap<>();

    private UiLayoutClient() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> onInit(event.getScreen()));
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Pre event) -> onRender(event.getScreen()));
        NeoForge.EVENT_BUS.addListener(UiLayoutClient::onEffectsRender);
        ScreenLayoutHooks.installEffects(UiLayoutService.INSTANCE);
    }

    private static void onInit(Screen screen) {
        UiLayoutStore.reloadIfChanged();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        // 元素可能挂在没登记面板的屏上（第三方容器屏），所以要在下面的 panelId 判断之前下发
        applyElementOffsets(container);
        ResourceLocation panelId = ScreenPanels.idFor(container);
        if (panelId == null) {
            return;
        }

        IScreenPanelAccess access = (IScreenPanelAccess) container;
        PanelRun run = new PanelRun(panelId, access.catoxides$panelLeft(), access.catoxides$panelTop());
        for (GuiEventListener child : container.children()) {
            // 配方书开关是原版唯一「按 leftPos 定位但之后不再跟随」的控件；只认精确类型，
            // 免得把 JEI/EMI 自己贴屏幕边的按钮也拖走。
            if (child instanceof ImageButton button && button.getClass() == ImageButton.class) {
                run.widgets.add(new WidgetAnchor(button,
                        button.getX() - run.baseLeft, button.getY() - run.baseTop));
            }
        }
        RUNNING.put(container, run);
    }

    /**
     * 把存下的偏移推给这一屏上的元素（{@code core.ui.UiEditTargets}）。
     *
     * <p>偏移相对元素自己的基准位置，所以面板被拖动、窗口缩放都不用改写存下的值；
     * 只有「上次在更大的窗口里存的偏移」要在这里按当前屏宽夹一次，
     * 夹过的值不回写文件——下次在同样大的窗口里打开还是原来的位置。
     */
    static void applyElementOffsets(AbstractContainerScreen<?> container) {
        for (IUiEditTarget target : UiEditTargets.present(container)) {
            UiRect base = target.baseBounds(container);
            UiPanelLayout stored = UiLayoutStore.getElement(target.id());
            target.setOffset(
                    UiGeometry.clampPanel(base.x() + stored.x(), container.width, base.width()) - base.x(),
                    UiGeometry.clampPanel(base.y() + stored.y(), container.height, base.height()) - base.y());
        }
    }

    /** 这一屏有我们的运行态（登记过、且已经初始化）；编辑模式只对这样的屏开。 */
    public static boolean isTracked(@Nullable Screen screen) {
        return screen instanceof AbstractContainerScreen<?> container && RUNNING.get(container) != null;
    }

    /**
     * 原版基准位置（不含偏移）。拖动时先用它还原出真正的基准：
     * 当前 {@code leftPos} 可能已经被夹过边，拿「当前 - 偏移」倒推会把基准算歪。
     */
    public static int baseLeft(AbstractContainerScreen<?> screen) {
        PanelRun run = RUNNING.get(screen);
        return run == null ? 0 : run.baseLeft;
    }

    public static int baseTop(AbstractContainerScreen<?> screen) {
        PanelRun run = RUNNING.get(screen);
        return run == null ? 0 : run.baseTop;
    }

    /**
     * 面板在屏幕上真正可见的高度：内容包登记了截底就只到截线，否则是原图高度。
     *
     * <p>夹边、F8 编辑框、效果列锚点都用这一个口径 —— 截掉的下半块不是面板，
     * 拿 {@code getYSize()} 会把面板当成还占着那块空间。
     */
    public static int visibleHeight(AbstractContainerScreen<?> screen) {
        Integer cut = ScreenCuts.visibleHeight(screen);
        return cut == null ? screen.getYSize() : Math.min(cut, screen.getYSize());
    }

    private static void onRender(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        PanelRun run = RUNNING.get(container);
        if (run == null) {
            return;
        }

        IScreenPanelAccess access = (IScreenPanelAccess) container;
        int currentLeft = access.catoxides$panelLeft();
        int currentTop = access.catoxides$panelTop();
        if (currentLeft != run.appliedLeft) {
            run.baseLeft = currentLeft;
        }
        if (currentTop != run.appliedTop) {
            run.baseTop = currentTop;
        }

        UiPanelLayout layout = UiLayoutStore.get(run.panelId);
        int targetLeft = UiGeometry.clampPanel(run.baseLeft + layout.x(), container.width, container.getXSize());
        int targetTop = UiGeometry.clampPanel(run.baseTop + layout.y(), container.height, visibleHeight(container));

        if (targetLeft != currentLeft) {
            access.catoxides$setPanelLeft(targetLeft);
        }
        if (targetTop != currentTop) {
            access.catoxides$setPanelTop(targetTop);
        }
        run.appliedLeft = targetLeft;
        run.appliedTop = targetTop;

        for (WidgetAnchor anchor : run.widgets) {
            anchor.widget().setX(targetLeft + anchor.relX());
            anchor.widget().setY(targetTop + anchor.relY());
        }
    }

    /**
     * 效果列的水平锚点。原版的 {@code leftPos + imageWidth + 2} 已经跟着面板走，
     * 所以只有 {@code gapX != 2} 时这一行才真的改变什么。
     */
    private static void onEffectsRender(ScreenEvent.RenderInventoryMobEffects event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        UiEffectsAnchor effects = UiLayoutStore.get(ScreenPanels.idFor(container)).effects();
        if (effects == null) {
            return;
        }
        IScreenPanelAccess access = (IScreenPanelAccess) container;
        event.setHorizontalOffset(effects.horizontalX(access.catoxides$panelLeft(), container.getXSize()));
    }

    /** 一个屏实例的状态；窗口缩放会重建屏，旧实例随 WeakHashMap 一起消失。 */
    private static final class PanelRun {

        private final ResourceLocation panelId;
        private final List<WidgetAnchor> widgets = new ArrayList<>(2);
        private int baseLeft;
        private int baseTop;
        private int appliedLeft;
        private int appliedTop;

        private PanelRun(ResourceLocation panelId, int baseLeft, int baseTop) {
            this.panelId = panelId;
            this.baseLeft = baseLeft;
            this.baseTop = baseTop;
            this.appliedLeft = baseLeft;
            this.appliedTop = baseTop;
        }
    }

    private record WidgetAnchor(AbstractWidget widget, int relX, int relY) {
    }
}
