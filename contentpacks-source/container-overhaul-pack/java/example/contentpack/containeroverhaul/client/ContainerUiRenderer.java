package example.contentpack.containeroverhaul.client;

import java.util.ArrayList;
import java.util.List;

import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;
import example.contentpack.containeroverhaul.ui.Panel;
import example.contentpack.containeroverhaul.ui.PanelMerge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 容器屏自绘部件的入口：收集各部件声明的面板，统一合并、画框、补格底。
 *
 * <p>挂在 {@code ContainerScreenEvent.Render.Background}：该事件在原版槽位循环之前触发
 * （{@code AbstractContainerScreen#render:137}），所以面板在物品下面；此时 Pose 还没平移到
 * {@code leftPos/topPos}（平移到 {@code :146} 才发生），坐标由部件自己加上屏幕原点。
 *
 * <p>叠放顺序 = 声明顺序：先是原版容器屏上半部分的外框，再是悬浮板。合并只发生在同层同画法的
 * 板之间，所以外框与悬浮板各自成型，不会因为贴在一起被并掉。
 */
public final class ContainerUiRenderer {

    private static final List<ContainerUiComponent> COMPONENTS = List.of(
            new ContainerUpperPanelComponent(),
            new InventoryPanelsComponent());

    private ContainerUiRenderer() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ContainerUiRenderer::onBackground);
    }

    private static void onBackground(ContainerScreenEvent.Render.Background event) {
        AbstractContainerScreen<?> screen = event.getContainerScreen();
        GuiGraphics graphics = event.getGuiGraphics();
        List<Panel> panels = new ArrayList<>();
        for (ContainerUiComponent component : COMPONENTS) {
            if (!component.present(screen)) {
                continue;
            }
            component.placeSlots(screen);
            panels.addAll(component.declare(screen));
        }
        for (Panel panel : PanelMerge.merge(panels)) {
            PanelPainter.paint(graphics, panel.rect(), panel.mode());
            for (UiRect cell : panel.cells()) {
                PanelPainter.paintSlot(graphics, cell.x(), cell.y());
            }
        }
    }
}
