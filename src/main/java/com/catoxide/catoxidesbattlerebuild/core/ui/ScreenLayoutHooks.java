package com.catoxide.catoxidesbattlerebuild.core.ui;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;

/**
 * 布局包 → core 的实现钩子，只给 mixin 用。
 *
 * <p>{@code EffectRenderingInventoryScreen#renderEffects} 把效果列画成「从 topPos 往下摞」，
 * 官方事件只能改水平位置；要改「贴在哪个角、往哪边磊」只能在外面平移整个方法的绘制。
 * mixin 因此只做「问一句偏移、平移、回退」——偏移怎么算是布局包的事。
 *
 * <p>没有布局包时返回 0，原版行为（R3）。
 */
public final class ScreenLayoutHooks {

    /** 由布局包实现。返回 {@code 0} = 不动。 */
    public interface Effects {

        int shiftY(AbstractContainerScreen<?> screen);
    }

    private static volatile Effects effects;

    private ScreenLayoutHooks() {
    }

    /** 布局包在 {@code register()} 阶段调用一次；后装者覆盖先装者。 */
    public static void installEffects(@Nullable Effects provider) {
        effects = provider;
    }

    public static int effectsShiftY(AbstractContainerScreen<?> screen) {
        Effects provider = effects;
        if (provider == null) {
            return 0;
        }
        try {
            return provider.shiftY(screen);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
