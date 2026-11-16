package example.contentpack.containeroverhaul.client;

import example.contentpack.containeroverhaul.ContainerOverhaulPack;
import example.contentpack.containeroverhaul.ui.SidePanel;
import example.contentpack.containeroverhaul.ui.SidePanelMounts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 画侧面板底板与锁定格。
 *
 * <p>挂在 {@code ContainerScreenEvent.Render.Background}：该事件在原版槽位循环之前触发
 * （{@code AbstractContainerScreen#render:137}），所以底板在物品下面；此时 Pose 还没平移到
 * {@code leftPos/topPos}（平移到 {@code :146} 才发生），坐标要自己加上屏幕原点。
 *
 * <p>槽位里的物品由原版 {@code renderSlot} 自动画 —— 面板槽位在菜单里，原版会遍历所有槽位。
 * 拖动偏移由 {@link SidePanelLayout} 维护，这里先让它把槽位落到目标位置再画底板，
 * 同一帧内的 {@code findSlot}/{@code renderSlot} 就会用新坐标。
 */
public final class SidePanelRenderer {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ContainerOverhaulPack.ID, "textures/gui/side_panel.png");

    private SidePanelRenderer() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(SidePanelRenderer::onBackground);
    }

    private static void onBackground(ContainerScreenEvent.Render.Background event) {
        AbstractContainerScreen<?> screen = event.getContainerScreen();
        SidePanelMounts.View view = SidePanelMounts.view(screen.getMenu());
        if (view == null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        SidePanelLayout layout = SidePanelLayout.INSTANCE;
        layout.place(view);
        int x = screen.getGuiLeft() + SidePanel.PANEL_X + layout.offsetX();
        int y = screen.getGuiTop() + SidePanel.PANEL_Y + layout.offsetY();
        graphics.blit(TEXTURE, x, y, 0.0F, 0.0F,
                SidePanel.WIDTH, SidePanel.HEIGHT, SidePanel.WIDTH, SidePanel.HEIGHT);

        int unlocked = SidePanel.unlockedCount(view.container());
        for (int i = unlocked; i < SidePanel.STORAGE_COUNT; i++) {
            int sx = x + SidePanel.STORAGE_X + (i % SidePanel.STORAGE_COLS) * SidePanel.SLOT_PITCH;
            int sy = y + SidePanel.STORAGE_Y + (i / SidePanel.STORAGE_COLS) * SidePanel.SLOT_PITCH;
            graphics.fill(sx, sy, sx + SidePanel.SLOT_PITCH, sy + SidePanel.SLOT_PITCH, 0x8C1A1A22);
        }
    }
}
