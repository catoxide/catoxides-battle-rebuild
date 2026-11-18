package example.contentpack.containeroverhaul.client;

import example.contentpack.containeroverhaul.ContainerOverhaulPack;
import example.contentpack.containeroverhaul.ui.SidePanel;
import example.contentpack.containeroverhaul.ui.SidePanelMounts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;

/**
 * 升级板：固定 4 格，整张 {@code upgrade.png} 1:1 画，挂在快捷栏下方。
 *
 * <p>与物品栏板同一套偏移纪律：{@link SidePanelLayout.UpgradePanel#place} 把偏移落到槽位，
 * 这里再用同一份偏移画底板。升级板跟着物品栏板联动，自己的偏移叠在联动位置之上。
 * 槽位物品由原版 {@code renderSlot} 画。
 */
final class UpgradePanelComponent implements ContainerUiComponent {

    private static final ResourceLocation UPGRADE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ContainerOverhaulPack.ID, "textures/gui/upgrade.png");

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
        SidePanelLayout.UpgradePanel upgrades = SidePanelLayout.upgrades();
        upgrades.place(view, screen.getXSize());

        int x = screen.getGuiLeft() + SidePanel.boardX(screen.getXSize())
                + SidePanelLayout.storage().offsetX() + upgrades.offsetX();
        int y = screen.getGuiTop() + SidePanel.upgradePanelY(view.hotbarY())
                + SidePanelLayout.storage().offsetY() + upgrades.offsetY();
        ContainerUiRenderer.blitBoard(graphics, UPGRADE_TEXTURE, x, y,
                SidePanel.UPGRADE_WIDTH, SidePanel.UPGRADE_HEIGHT);
    }
}
