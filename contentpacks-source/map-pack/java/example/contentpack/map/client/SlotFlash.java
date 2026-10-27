package example.contentpack.map.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;

/**
 * 容器界面里用 Mouse4 标记物品后，对应槽位像 JEI 一样闪一下背景。
 *
 * <p>只在同一个界面实例上闪；关掉界面/切屏后自动失效。
 */
final class SlotFlash {

    private static final long DURATION_MILLIS = 1_200L;

    private static Screen screen;
    private static Slot slot;
    private static long start;
    private static long until;

    private SlotFlash() {
    }

    static void flash(Screen current, Slot target) {
        screen = current;
        slot = target;
        start = System.currentTimeMillis();
        until = start + DURATION_MILLIS;
    }

    static void tick() {
        if (slot != null && System.currentTimeMillis() > until) {
            clear();
        }
    }

    /** 背景层之后、物品之前绘制：高亮在物品下面，也不会盖住 tooltip。 */
    static void render(ContainerScreenEvent.Render.Background event) {
        Slot target = slot;
        Screen owner = screen;
        AbstractContainerScreen<?> container = event.getContainerScreen();
        if (target == null || owner == null || container != owner) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now > until) {
            clear();
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        double phase = (now - start) / (double) DURATION_MILLIS * Math.PI * 6.0;
        float alpha = (float) (0.28 + 0.22 * (0.5 + 0.5 * Math.sin(phase)));
        int color = ((int) (alpha * 255.0f) << 24) | 0x00FFD54A;
        int x = container.getGuiLeft() + target.x;
        int y = container.getGuiTop() + target.y;
        graphics.fill(x, y, x + 16, y + 16, color);
        graphics.fill(x, y, x + 16, y + 1, 0xFFFFE08A);
        graphics.fill(x, y + 15, x + 16, y + 16, 0xFFFFE08A);
        graphics.fill(x, y, x + 1, y + 16, 0xFFFFE08A);
        graphics.fill(x + 15, y, x + 16, y + 16, 0xFFFFE08A);
    }

    private static void clear() {
        screen = null;
        slot = null;
        until = 0L;
    }
}
