package example.contentpack.containeroverhaul.client;

import java.util.ArrayList;
import java.util.List;

import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;
import example.contentpack.containeroverhaul.ui.Panel;
import example.contentpack.containeroverhaul.ui.SidePanel;
import example.contentpack.containeroverhaul.ui.SidePanelMounts;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * 物品栏板 + 升级板：先交两块同层的整板面板，渲染器把贴在一起的并成一块再画。
 *
 * <p>合并只影响画框，不影响槽位：{@code placeSlots} 仍按各自板的基准位置落槽，
 * 所以两块板贴在一起时格子停在自己的位置上，只是中间那条缝的两道边框合成了一道。
 *
 * <p>格底跟着面板一起声明（1.21 的原版 {@code renderSlot} 只画物品与悬停高亮）：
 * 格底位置就是槽位坐标左上各 1px，锁着的行不画。
 */
final class InventoryPanelsComponent implements ContainerUiComponent {

    @Override
    public boolean present(AbstractContainerScreen<?> screen) {
        return SidePanelMounts.view(screen.getMenu()) != null;
    }

    @Override
    public void placeSlots(AbstractContainerScreen<?> screen) {
        SidePanelMounts.View view = SidePanelMounts.view(screen.getMenu());
        if (view == null) {
            return;
        }
        SidePanelLayout.InventoryBoard board = SidePanelLayout.storage();
        SidePanelLayout.UpgradePanel upgrades = SidePanelLayout.upgrades();
        board.place(view, screen.getXSize());
        upgrades.place(view, screen.getXSize());
    }

    @Override
    public List<Panel> declare(AbstractContainerScreen<?> screen) {
        SidePanelMounts.View view = SidePanelMounts.view(screen.getMenu());
        if (view == null) {
            return List.of();
        }
        SidePanelLayout.InventoryBoard board = SidePanelLayout.storage();
        SidePanelLayout.UpgradePanel upgrades = SidePanelLayout.upgrades();

        int rows = SidePanel.boardRows(view.upgrades());
        int boardX = screen.getGuiLeft() + SidePanel.boardX(screen.getXSize()) + board.offsetX();
        int boardY = screen.getGuiTop() + SidePanel.boardY(view.hotbarY(), rows) + board.offsetY();
        UiRect boardRect = new UiRect(boardX, boardY, SidePanel.ROW_WIDTH, SidePanel.boardHeight(rows));
        UiRect upgradeRect = new UiRect(
                boardX + upgrades.offsetX(),
                screen.getGuiTop() + SidePanel.upgradePanelY(view.hotbarY())
                        + board.offsetY() + upgrades.offsetY(),
                SidePanel.UPGRADE_WIDTH, SidePanel.UPGRADE_HEIGHT);

        int unlocked = SidePanel.unlockedCount(view.upgrades());
        List<UiRect> boardCells = new ArrayList<>();
        for (Slot slot : view.itemSlots()) {
            if (slot.getContainerSlot() >= unlocked) {
                break;
            }
            boardCells.add(cell(screen, slot));
        }
        List<UiRect> upgradeCells = new ArrayList<>();
        for (Slot slot : view.upgradeSlots()) {
            upgradeCells.add(cell(screen, slot));
        }
        return List.of(
                Panel.body(boardRect, Panel.FLOATING, boardCells),
                Panel.body(upgradeRect, Panel.FLOATING, upgradeCells));
    }

    /** 格底 = 槽位坐标左上各 1px（原版 renderSlot 画在 slot.x-1），18×18 原版 slot 精灵。 */
    private static UiRect cell(AbstractContainerScreen<?> screen, Slot slot) {
        return new UiRect(screen.getGuiLeft() + slot.x - 1, screen.getGuiTop() + slot.y - 1,
                PanelPainter.SLOT_SIZE, PanelPainter.SLOT_SIZE);
    }
}
