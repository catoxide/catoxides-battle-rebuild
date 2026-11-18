package example.contentpack.containeroverhaul.client;

import com.catoxide.catoxidesbattlerebuild.core.ui.ScreenCuts;
import example.contentpack.containeroverhaul.ui.SidePanelMounts;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import javax.annotation.Nullable;

/**
 * 声明哪些容器屏要截掉下半块（原版玩家物品栏块）。
 *
 * <p>只认已挂载悬浮物品栏的屏（{@link SidePanelMounts#view} 非空）：没有挂载时不动原版布局。
 * 截线不按屏硬编码，也不是现算的：原版各屏都把玩家主栏（容器下标 9–35）放在容器内容下方，
 * 挂载时取这些槽位的最高顶边 - 1 存进 {@code View#cutHeight}（箱子行数不同、漏斗 51、信标 137……
 * 同一套算法都成立）。必须用挂载时的快照 —— 之后浮板每帧把这些槽位挪走，槽位坐标不再是原版的。
 *
 * <p>具体裁剪由 core 的 {@code ContainerBackgroundShiftMixin} 统一做。
 */
public final class ContainerCut implements ScreenCuts.Provider {

    private static final ContainerCut INSTANCE = new ContainerCut();

    private ContainerCut() {}

    public static void register() {
        ScreenCuts.register(INSTANCE);
    }

    @Override
    public @Nullable Integer visibleHeight(AbstractContainerScreen<?> screen) {
        SidePanelMounts.View view = SidePanelMounts.view(screen.getMenu());
        if (view == null || view.cutHeight() < 0) {
            return null;
        }
        return view.cutHeight();
    }
}
