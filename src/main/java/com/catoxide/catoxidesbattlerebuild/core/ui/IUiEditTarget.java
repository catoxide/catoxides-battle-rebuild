package com.catoxide.catoxidesbattlerebuild.core.ui;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;

/**
 * 能在 F8 容器编辑模式里拖动的元素（包→core 的内容登记轴，同 {@link ScreenPanels}）。
 *
 * <p>元素自己决定「在哪、多大」，布局层只叠加偏移并落盘；尺寸永远归元素所属的包
 * （与 HUD 布局契约 §3 同一条纪律，容器侧见 {@code design/RESEARCH_2026-11-12_容器界面布局契约.md} §6.6）。
 *
 * <ul>
 *   <li>{@link #id()} 是 {@code ui-layout.json} 里 {@code elements} 段的键，必须全局唯一；</li>
 *   <li>{@link #baseBounds} 返回<b>偏移为 0</b> 时的矩形（通常由 {@code leftPos/topPos} 推出），
 *       所以面板被挪动、窗口缩放都不需要改写存下的值；</li>
 *   <li>{@link #setOffset} 只由布局包调用；没有布局包时永远收不到调用，元素停在原版位置（R3）。</li>
 * </ul>
 */
public interface IUiEditTarget {

    ResourceLocation id();

    /** 这一屏上现在有没有这个元素（菜单里有没有挂上它）。 */
    boolean present(AbstractContainerScreen<?> screen);

    UiRect baseBounds(AbstractContainerScreen<?> screen);

    /** 当前生效的偏移（布局层下发、元素自己保存的那一份）。 */
    int offsetX();

    int offsetY();

    void setOffset(int x, int y);
}
