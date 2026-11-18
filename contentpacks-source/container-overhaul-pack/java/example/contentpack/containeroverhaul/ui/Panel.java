package example.contentpack.containeroverhaul.ui;

import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;

import java.util.List;

/**
 * 一块面板的声明：矩形、层级、画法、格底。所有自绘面板都按这一种形状交出来，
 * 由渲染器统一走「同层同画法合并 → 画框 → 补格底」，没有第二套写法。
 *
 * <p>层级只决定合并：层级相同的板贴边时并成一块（接缝处两条边框合成一条），
 * 层级不同的板即使贴上去了也各画各的。画法只决定板身：{@link Mode#BODY} 连板身一起现画，
 * {@link Mode#FRAME} 只画 4px 外框、内部留给原版贴图。
 *
 * <p>{@code cells} 是这块板里要补画的格底（左上角 + 18×18 原版 slot 精灵）。1.21 的原版
 * {@code renderSlot} 只画物品与悬停高亮，格底是容器贴图的一部分；面板被现画之后原版格底
 * 就没了，所以要跟声明一起交出来，保证「板画到哪、格底就在哪」。
 */
public record Panel(UiRect rect, int level, Mode mode, List<UiRect> cells) {

    public enum Mode {
        /** 整块现画：外框 + 板身。 */
        BODY,
        /** 只换外框：4px 边框压在原版边框上，内部一个像素不动。 */
        FRAME
    }

    /** 原版容器屏上半部分：只换外框，内部仍由原版贴图画。 */
    public static final int DOCKED = 0;
    /** 悬浮板（物品栏 / 升级板）：整块现画，贴边合并。 */
    public static final int FLOATING = 1;

    public Panel {
        cells = List.copyOf(cells);
    }

    public static Panel body(UiRect rect, int level, List<UiRect> cells) {
        return new Panel(rect, level, Mode.BODY, cells);
    }

    public static Panel frame(UiRect rect, int level) {
        return new Panel(rect, level, Mode.FRAME, List.of());
    }
}
