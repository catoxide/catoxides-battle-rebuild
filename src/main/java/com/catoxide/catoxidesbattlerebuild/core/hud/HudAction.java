package com.catoxide.catoxidesbattlerebuild.core.hud;

import net.minecraft.network.chat.Component;

/**
 * 右键菜单项（框架）：条目由元素自己提供，布局系统只负责画和派发。
 *
 * <p>元素在自己的 {@link HudElement#actions()} 里返回可选项（例：切换样式、切换横竖、缩放）。
 * 布局包不预置任何元素专属功能，只在编辑屏里提供通用的「重置位置」。
 */
public record HudAction(Component label, Runnable action) {
}
