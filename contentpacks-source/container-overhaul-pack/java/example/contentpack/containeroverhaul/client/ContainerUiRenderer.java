package example.contentpack.containeroverhaul.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 容器屏自绘部件的入口：所有部件共挂一个事件监听。
 *
 * <p>挂在 {@code ContainerScreenEvent.Render.Background}：该事件在原版槽位循环之前触发
 * （{@code AbstractContainerScreen#render:137}），所以底板在物品下面；此时 Pose 还没平移到
 * {@code leftPos/topPos}（平移到 {@code :146} 才发生），坐标由部件自己加上屏幕原点。
 *
 * <p>顺序即叠放顺序：底边条在最底，物品栏板与升级板（同层、贴边即合并）其次。
 * 部件自己判断是否出场，这里不做任何前提判断 —— 尤其不再因为侧面板缺席就整屏跳过。
 */
public final class ContainerUiRenderer {

    private static final List<ContainerUiComponent> COMPONENTS = List.of(
            new ContainerFloorComponent(),
            new InventoryPanelsComponent());

    private ContainerUiRenderer() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ContainerUiRenderer::onBackground);
    }

    private static void onBackground(ContainerScreenEvent.Render.Background event) {
        AbstractContainerScreen<?> screen = event.getContainerScreen();
        GuiGraphics graphics = event.getGuiGraphics();
        for (ContainerUiComponent component : COMPONENTS) {
            if (component.present(screen)) {
                component.render(graphics, screen);
            }
        }
    }
}
