package example.contentpack.containeroverhaul.client;

import example.contentpack.containeroverhaul.ContainerOverhaulPack;
import example.contentpack.containeroverhaul.ui.SidePanel;
import example.contentpack.containeroverhaul.ui.SidePanelMounts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;

/**
 * 悬浮物品栏板：快捷栏在最下一行，背包行按解锁数量往上长，按行数换整板贴图，不拉伸。
 *
 * <p>{@code board_1..board_5.png} 是 1..5 行各一张整板（边框 + 格子已烘进图里），
 * 高度 = 行数 × 18 + 快捷栏缝 + 上下各 4px；未解锁的行没有图，板子只长到解锁的那一行。
 * 槽位里的物品由原版 {@code renderSlot} 自动画，位置由 {@link SidePanelLayout} 每帧落到板上。
 *
 * <p>这是原版「物品栏块」的替代：它把 0–44 全部物品格挪到板子上，原版那块由
 * {@link ContainerCut} 截掉。
 */
final class PlayerInventoryFloatComponent implements ContainerUiComponent {

    /** 下标 = 板行数（1–5）；0 号占位不用。 */
    private static final ResourceLocation[] BOARD_TEXTURES =
            new ResourceLocation[SidePanel.BACKPACK_ROWS + 2];

    static {
        for (int rows = 1; rows < BOARD_TEXTURES.length; rows++) {
            BOARD_TEXTURES[rows] = ResourceLocation.fromNamespaceAndPath(
                    ContainerOverhaulPack.ID, "textures/gui/board_" + rows + ".png");
        }
    }

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
        board.place(view, screen.getXSize());

        int rows = SidePanel.boardRows(view.upgrades());
        int x = screen.getGuiLeft() + SidePanel.boardX(screen.getXSize()) + board.offsetX();
        int y = screen.getGuiTop() + SidePanel.boardY(view.hotbarY(), rows) + board.offsetY();
        ContainerUiRenderer.blitBoard(graphics, BOARD_TEXTURES[rows], x, y,
                SidePanel.ROW_WIDTH, SidePanel.boardHeight(rows));
    }
}
