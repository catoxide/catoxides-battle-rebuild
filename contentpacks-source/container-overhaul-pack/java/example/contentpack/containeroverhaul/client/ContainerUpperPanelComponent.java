package example.contentpack.containeroverhaul.client;

import java.util.List;

import com.catoxide.catoxidesbattlerebuild.core.ui.ScreenCuts;
import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;
import example.contentpack.containeroverhaul.ui.Panel;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * 原版容器屏的上半部分：用和悬浮板同一套 skin 把外框换掉，底边收在截线上。
 *
 * <p>只换外框（{@link Panel.Mode#FRAME}）：容器自己的槽位格底、标题、内部装饰都烘在原版贴图里，
 * 盖板身会把它们一起盖掉；4px 外框正好压在原版边框上，内部一个像素不动。
 *
 * <p>矩形高度 = 可见高度 + 外框厚度：上 / 左 / 右三边压在原版边框上，最底 4 行
 * （板身 / 暗棱 / 暗棱 / 黑边）落在截线以下，被裁掉的下半块由这条底边收口 —— 宽度取这一屏
 * 自己的面板宽（176 / 230 / 256……），不再按宽度烘底边条。
 *
 * <p>没有截底登记的屏不出场（R3）：外框不 override 原版，屏保持原样。
 */
final class ContainerUpperPanelComponent implements ContainerUiComponent {

    @Override
    public boolean present(AbstractContainerScreen<?> screen) {
        return ScreenCuts.visibleHeight(screen) != null;
    }

    @Override
    public List<Panel> declare(AbstractContainerScreen<?> screen) {
        Integer visible = ScreenCuts.visibleHeight(screen);
        if (visible == null) {
            return List.of();
        }
        UiRect rect = new UiRect(screen.getGuiLeft(), screen.getGuiTop(), screen.getXSize(),
                visible + PanelPainter.CORNER);
        return List.of(Panel.frame(rect, Panel.DOCKED));
    }
}
