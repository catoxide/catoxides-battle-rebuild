package com.catoxide.catoxidesbattlerebuild.core.ui;

/**
 * 容器面板的字段读写口（由 mixin 实现到 {@code AbstractContainerScreen} 上）。
 *
 * <p>原版把 {@code leftPos/topPos/imageWidth/imageHeight} 声明成 {@code protected} 字段，
 * 内容包在别的包里写不了；原版也没有 setter。读的一侧本来有公开访问器
 * （{@code getGuiLeft/getXSize}），写的一侧只能靠这条接口。
 *
 * <p>只做字段搬运，不含任何布局判断——偏移怎么算、什么时候写住内容包。
 */
public interface IScreenPanelAccess {

    int catoxides$panelLeft();

    int catoxides$panelTop();

    void catoxides$setPanelLeft(int left);

    void catoxides$setPanelTop(int top);
}
