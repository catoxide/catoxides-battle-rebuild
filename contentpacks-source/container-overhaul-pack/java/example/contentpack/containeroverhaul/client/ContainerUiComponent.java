package example.contentpack.containeroverhaul.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * 容器屏上的一块自绘 UI。每个部件自己判断「这一屏有没有我」，再自己画。
 *
 * <p>部件之间不相互引用：物品栏底板只看菜单里的物品栏槽位，储物/升级板只看
 * {@code SidePanelMounts}。所以某一块缺席不会带走其它块 —— 侧面板没挂上时底板照画。
 *
 * <p>画在 {@code ContainerScreenEvent.Render.Background} 里（见 {@link ContainerUiRenderer}），
 * 位于原版背景之上、物品之下；坐标要自己加上屏幕原点。
 */
interface ContainerUiComponent {

    /** 这一屏上现在有没有这个部件。 */
    boolean present(AbstractContainerScreen<?> screen);

    void render(GuiGraphics graphics, AbstractContainerScreen<?> screen);
}
