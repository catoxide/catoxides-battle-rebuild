package example.contentpack.containeroverhaul.client;

import java.util.List;

import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;
import example.contentpack.containeroverhaul.ui.PanelMerge;
import example.contentpack.containeroverhaul.ui.SidePanel;
import example.contentpack.containeroverhaul.ui.SidePanelMounts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * 物品栏板 + 升级板：两块各交一个矩形和层级，{@link PanelMerge} 把贴在一起的并成一块，
 * {@link PanelPainter} 按合并后的尺寸现画面板框。
 *
 * <p>合并只影响画框，不影响槽位：{@code place()} 仍按各自板的基准位置落槽，
 * 所以两块板贴在一起时格子停在自己的位置上，只是中间那条缝的两道边框合成了一道。
 *
 * <p>格底在原版 {@code renderSlot} 之外补画（1.21 的 {@code renderSlot} 只画物品与悬停高亮）：
 * 格底位置就是槽位坐标左上各 1px，跟落好位置的槽位永远一致；锁着的行不画格底。
 */
final class InventoryPanelsComponent implements ContainerUiComponent {

    /** 物品栏板与升级板同层：贴在一起就合并。层级不同的板永远各画各的框。 */
    private static final int LEVEL_MAIN = 0;

    @Override
    public boolean present(AbstractContainerScreen<?> screen) {
        return SidePanelMounts.view(screen.getMenu()) != null;
    }

    @Override
    public void render(GuiGraphics graphics, AbstractContainerScreen<?> screen) {
        SidePanelMounts.View view = SidePanelMounts.view(screen.getMenu());
        if (view == null) {
            return;
        }
        SidePanelLayout.InventoryBoard board = SidePanelLayout.storage();
        SidePanelLayout.UpgradePanel upgrades = SidePanelLayout.upgrades();
        board.place(view, screen.getXSize());
        upgrades.place(view, screen.getXSize());

        int rows = SidePanel.boardRows(view.upgrades());
        int boardX = screen.getGuiLeft() + SidePanel.boardX(screen.getXSize()) + board.offsetX();
        int boardY = screen.getGuiTop() + SidePanel.boardY(view.hotbarY(), rows) + board.offsetY();
        UiRect boardRect = new UiRect(boardX, boardY, SidePanel.ROW_WIDTH, SidePanel.boardHeight(rows));
        UiRect upgradeRect = new UiRect(
                boardX + upgrades.offsetX(),
                screen.getGuiTop() + SidePanel.upgradePanelY(view.hotbarY())
                        + board.offsetY() + upgrades.offsetY(),
                SidePanel.UPGRADE_WIDTH, SidePanel.UPGRADE_HEIGHT);

        List<UiRect> panels = PanelMerge.merge(List.of(
                new PanelMerge.Section(boardRect, LEVEL_MAIN),
                new PanelMerge.Section(upgradeRect, LEVEL_MAIN)));
        for (UiRect panel : panels) {
            PanelPainter.paint(graphics, panel);
        }
        paintCells(graphics, screen, view);
    }

    /** 已解锁的物品格 + 升级格的格底；槽位坐标是屏内的，这里补上屏原点。 */
    private static void paintCells(GuiGraphics graphics, AbstractContainerScreen<?> screen,
                                   SidePanelMounts.View view) {
        int unlocked = SidePanel.unlockedCount(view.upgrades());
        for (Slot slot : view.itemSlots()) {
            if (slot.getContainerSlot() >= unlocked) {
                break;
            }
            PanelPainter.paintSlot(graphics, screen.getGuiLeft() + slot.x - 1,
                    screen.getGuiTop() + slot.y - 1);
        }
        for (Slot slot : view.upgradeSlots()) {
            PanelPainter.paintSlot(graphics, screen.getGuiLeft() + slot.x - 1,
                    screen.getGuiTop() + slot.y - 1);
        }
    }
}
