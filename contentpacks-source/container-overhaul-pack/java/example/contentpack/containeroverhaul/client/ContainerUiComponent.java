package example.contentpack.containeroverhaul.client;

import example.contentpack.containeroverhaul.ui.Panel;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.List;

/**
 * 容器屏上的一块自绘 UI。每个部件自己判断「这一屏有没有我」，再交出这一帧的面板声明。
 *
 * <p>部件不直接画：渲染器把全部部件的声明收在一起，统一走「同层同画法合并 → 画框 → 补格底」，
 * 合并规则只有一份，新面板只要按 {@link Panel} 的形状交出来就接进同一套流程。
 *
 * <p>需要挪槽位的部件（浮板）先 {@link #placeSlots} 再 {@link #declare}：格底坐标取自
 * 摆好之后的槽位，两者永远一致。
 */
interface ContainerUiComponent {

    /** 这一屏上现在有没有这个部件。 */
    boolean present(AbstractContainerScreen<?> screen);

    /** 把这一屏的槽位摆到本部件的位置；不挪槽位的部件不用实现。 */
    default void placeSlots(AbstractContainerScreen<?> screen) {
    }

    /** 这一帧要画的面板。 */
    List<Panel> declare(AbstractContainerScreen<?> screen);
}
