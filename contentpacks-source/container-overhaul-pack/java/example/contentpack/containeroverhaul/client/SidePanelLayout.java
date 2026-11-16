package example.contentpack.containeroverhaul.client;

import com.catoxide.catoxidesbattlerebuild.core.ui.AuxPanels;
import com.catoxide.catoxidesbattlerebuild.core.ui.IUiEditTarget;
import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;
import example.contentpack.containeroverhaul.ui.SidePanel;
import example.contentpack.containeroverhaul.ui.SidePanelMounts;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;

/**
 * 侧面板的位置：基准是「容器左边缘 + {@link SidePanel#PANEL_X}」，偏移由 hud-layout-pack 的
 * F8 容器编辑模式下发（{@link IUiEditTarget}）。
 *
 * <p>没有布局包时 {@link #setOffset} 永远不会被调用，偏移保持 0 = 原版位置（R3）。
 * 本包不再自己收鼠标事件：拖动入口统一在布局包，免得两套拖拽各记一份状态。
 *
 * <p>偏移只改客户端状态：{@code Slot.x/y} 不进同步包，服务端仍按 {@code slot.index} 处理点击，
 * 所以「面板画在哪」与「服务端认哪一格」互不影响。槽位坐标每帧在画底板前重算
 * （{@link #place}），菜单重建 / 屏幕重排都不会和这里的偏移抢同一份数据。
 */
public final class SidePanelLayout implements IUiEditTarget {

    public static final SidePanelLayout INSTANCE = new SidePanelLayout();

    private int offsetX;
    private int offsetY;

    private SidePanelLayout() {}

    /** 与槽位挂载同一个 id：一个附属窗只存在一份位置，{@code ui-layout.json} 里也只有一条。 */
    @Override
    public ResourceLocation id() {
        return SidePanelMounts.PANEL_ID;
    }

    @Override
    public boolean present(AbstractContainerScreen<?> screen) {
        return SidePanelMounts.view(screen.getMenu()) != null;
    }

    @Override
    public UiRect baseBounds(AbstractContainerScreen<?> screen) {
        return new UiRect(screen.getGuiLeft() + SidePanel.PANEL_X, screen.getGuiTop() + SidePanel.PANEL_Y,
                SidePanel.WIDTH, SidePanel.HEIGHT);
    }

    @Override
    public int offsetX() {
        return offsetX;
    }

    @Override
    public int offsetY() {
        return offsetY;
    }

    @Override
    public void setOffset(int x, int y) {
        offsetX = x;
        offsetY = y;
    }

    /** 把面板槽位落到「基准格点 + 当前偏移」；渲染器每帧调用一次。 */
    public void place(SidePanelMounts.View view) {
        for (Slot slot : view.slots()) {
            int index = slot.getContainerSlot();
            AuxPanels.moveSlot(slot, SidePanel.slotX(index) + offsetX, SidePanel.slotY(index) + offsetY);
        }
    }
}
