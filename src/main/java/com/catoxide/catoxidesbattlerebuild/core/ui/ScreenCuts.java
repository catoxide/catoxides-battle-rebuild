package com.catoxide.catoxidesbattlerebuild.core.ui;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 容器屏「截底」登记处：内容包声明一屏的背景最多画到多高，core 用 scissor 把下面裁掉。
 *
 * <p>不是盖一层不透明图 —— 盖图会挡住世界背景，看上去仍是一整块 UI；scissor 裁掉之后
 * 下半块真正不画，露出来的是世界。槽位、物品、快捷栏高亮仍走原版路径，只是坐标被内容包挪走。
 *
 * <p>返回的是「可见高度」（从面板顶边算起），多个内容包取最小值；没有登记 = 不裁（R3）。
 * 背景与标签用同一块裁剪，避免标签留在被裁掉的位置上。
 */
public final class ScreenCuts {

    @FunctionalInterface
    public interface Provider {
        /**
         * @return 可见高度（面板局部坐标，0 = 面板顶边），{@code null} 表示这一屏不裁
         */
        @Nullable
        Integer visibleHeight(AbstractContainerScreen<?> screen);
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private ScreenCuts() {}

    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    @Nullable
    public static Integer visibleHeight(AbstractContainerScreen<?> screen) {
        Integer visible = null;
        for (Provider provider : PROVIDERS) {
            Integer candidate = provider.visibleHeight(screen);
            if (candidate != null) {
                visible = visible == null ? candidate : Math.min(visible, candidate);
            }
        }
        return visible;
    }
}
