package example.contentpack.containeroverhaul.client;

import com.catoxide.catoxidesbattlerebuild.core.ui.AuxPanels;
import com.catoxide.catoxidesbattlerebuild.core.ui.IUiEditTarget;
import com.catoxide.catoxidesbattlerebuild.core.ui.UiEditTargets;
import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;
import example.contentpack.containeroverhaul.ui.SidePanel;
import example.contentpack.containeroverhaul.ui.SidePanelMounts;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;

/**
 * 悬浮物品栏的布局：物品栏板一块（{@code storage}，快捷栏在底、背包行往上）+ 升级板一块（{@code upgrades}），
 * 各自记自己的偏移。
 *
 * <p>偏移由 hud-layout-pack 的 F8 容器编辑模式下发（{@link IUiEditTarget}）。没有布局包时
 * {@link #setOffset} 永远不会被调用，偏移保持 0 = 本包算的基准位置（R3）；本包不收鼠标事件，
 * 拖动入口统一在布局包。
 *
 * <p>两块板的偏移<b>串联</b>：升级板挂在快捷栏下方，物品栏板被拖走时它跟着走，
 * 自己的偏移再叠在联动位置上面（见 {@link UpgradePanel#baseBounds}）。
 *
 * <p>偏移只改客户端状态：{@code Slot.x/y} 不进同步包，服务端仍按 {@code slot.index} 处理点击，
 * 所以「浮板画在哪」与「服务端认哪一格」互不影响。槽位坐标每帧在画底板前重算
 * （{@link InventoryBoard#place}/{@link UpgradePanel#place}），菜单重建 / 屏幕重排都不会和这里的偏移抢同一份数据。
 */
public final class SidePanelLayout {

    private static final InventoryBoard STORAGE = new InventoryBoard();
    private static final UpgradePanel UPGRADES = new UpgradePanel();

    private SidePanelLayout() {}

    /** 登记成 F8 可拖元素（由包的 client 初始化调用一次）。 */
    public static void register() {
        UiEditTargets.register(STORAGE);
        UiEditTargets.register(UPGRADES);
    }

    public static InventoryBoard storage() {
        return STORAGE;
    }

    public static UpgradePanel upgrades() {
        return UPGRADES;
    }

    /** 两块浮板共用的偏移状态；id / 基准矩形各自实现。 */
    public abstract static class FloatPanel implements IUiEditTarget {

        private int offsetX;
        private int offsetY;

        @Override
        public final int offsetX() {
            return offsetX;
        }

        @Override
        public final int offsetY() {
            return offsetY;
        }

        @Override
        public final void setOffset(int x, int y) {
            offsetX = x;
            offsetY = y;
        }

        final boolean mounted(AbstractContainerScreen<?> screen) {
            return SidePanelMounts.view(screen.getMenu()) != null;
        }
    }

    /** 物品栏板：一整块，高度 = (快捷栏 1 行 + 已解锁背包行) × 行高；板顶随行数上移。 */
    public static final class InventoryBoard extends FloatPanel {

        private InventoryBoard() {}

        @Override
        public ResourceLocation id() {
            return ResourceLocation.fromNamespaceAndPath(SidePanel.NAMESPACE, "storage");
        }

        @Override
        public boolean present(AbstractContainerScreen<?> screen) {
            return mounted(screen);
        }

        @Override
        public UiRect baseBounds(AbstractContainerScreen<?> screen) {
            SidePanelMounts.View view = SidePanelMounts.view(screen.getMenu());
            int rows = view == null ? 1 : SidePanel.boardRows(view.upgrades());
            int hotbarY = view == null ? SidePanel.DEFAULT_HOTBAR_Y : view.hotbarY();
            return new UiRect(screen.getGuiLeft() + SidePanel.boardX(screen.getXSize()),
                    screen.getGuiTop() + SidePanel.boardY(hotbarY, rows),
                    SidePanel.ROW_WIDTH, SidePanel.boardHeight(rows));
        }

        /** 把全部 0–44 物品槽落到「基准格点 + 当前偏移」；渲染器每帧调用一次。 */
        public void place(SidePanelMounts.View view, int guiWidth) {
            for (Slot slot : view.itemSlots()) {
                int containerSlot = slot.getContainerSlot();
                AuxPanels.moveSlot(slot,
                        SidePanel.slotX(guiWidth, containerSlot) + offsetX(),
                        SidePanel.slotY(view.hotbarY(), containerSlot) + offsetY());
            }
        }
    }

    /** 升级槽：挂在快捷栏下方的 4 格浮板。 */
    public static final class UpgradePanel extends FloatPanel {

        private UpgradePanel() {}

        @Override
        public ResourceLocation id() {
            return ResourceLocation.fromNamespaceAndPath(SidePanel.NAMESPACE, "upgrades");
        }

        @Override
        public boolean present(AbstractContainerScreen<?> screen) {
            return mounted(screen);
        }

        /**
         * 基准矩形含物品栏板的偏移：升级板挂在快捷栏下方，物品栏板被拖走时必须一起走。
         * 这样 F8 的框、命中区、槽位、底板四处用的是同一份联动位置；
         * 升级板自己的偏移仍然相对这个联动基准，单独拖它也不受影响。
         */
        @Override
        public UiRect baseBounds(AbstractContainerScreen<?> screen) {
            SidePanelMounts.View view = SidePanelMounts.view(screen.getMenu());
            int hotbarY = view == null ? SidePanel.DEFAULT_HOTBAR_Y : view.hotbarY();
            return new UiRect(screen.getGuiLeft() + SidePanel.boardX(screen.getXSize()) + STORAGE.offsetX(),
                    screen.getGuiTop() + SidePanel.upgradePanelY(hotbarY) + STORAGE.offsetY(),
                    SidePanel.UPGRADE_WIDTH, SidePanel.UPGRADE_HEIGHT);
        }

        public void place(SidePanelMounts.View view, int guiWidth) {
            int linkX = STORAGE.offsetX() + offsetX();
            int linkY = STORAGE.offsetY() + offsetY();
            for (Slot slot : view.upgradeSlots()) {
                int col = slot.getContainerSlot();
                AuxPanels.moveSlot(slot,
                        SidePanel.upgradeSlotX(guiWidth, col) + linkX,
                        SidePanel.upgradeSlotY(view.hotbarY()) + linkY);
            }
        }
    }
}
