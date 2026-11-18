package com.catoxide.catoxidesbattlerebuild.core.ui;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 屏 → 面板 id 的登记表（包→core 的内容登记轴，同 {@code HudElements}）。
 *
 * <p>原版容器屏由布局包在 {@code register()} 里登记；第三方屏由它的主人自己登记（opt-in）。
 * 没登记的屏布局层一律不碰——按类名或尺寸猜是禁区，猜错会把别人的面板挪走。
 *
 * <p>查找按类继承链向上走：登记 {@code InventoryScreen} 不会顺带管住它的子类以外的屏，
 * 但子类会命中父类的 id。同一个类登记两次保留第一次。
 */
public final class ScreenPanels {

    private static final String TAG = "UiLayout";
    private static final Map<Class<?>, ResourceLocation> PANELS = new ConcurrentHashMap<>();

    private ScreenPanels() {
    }

    public static void register(Class<? extends Screen> screenClass, ResourceLocation panelId) {
        Objects.requireNonNull(screenClass, "screenClass");
        Objects.requireNonNull(panelId, "panelId");

        ResourceLocation previous = PANELS.putIfAbsent(screenClass, panelId);
        if (previous != null && !previous.equals(panelId)) {
            LogManager.clientWarn(TAG, "Screen '%s' was registered as panel '%s'; keeping '%s'",
                    screenClass.getName(), previous, panelId);
        }
    }

    public static @Nullable ResourceLocation idFor(@Nullable Screen screen) {
        return screen == null ? null : idOfClass(screen.getClass());
    }

    /** 类继承链查找；单独放开是为了让自检不依赖真的构造一个屏。 */
    public static @Nullable ResourceLocation idOfClass(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            ResourceLocation id = PANELS.get(current);
            if (id != null) {
                return id;
            }
        }
        return null;
    }
}
