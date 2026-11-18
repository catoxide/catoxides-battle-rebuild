package com.catoxide.catoxidesbattlerebuild.core.ui;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器屏可编辑元素的登记表（静态注册表 + 接口，同 {@code HudElements}）。
 *
 * <p>core 不认识任何包类；包在自己的 {@code register()} 里登记，布局包从 {@link #present}
 * 读这一屏上能拖的元素。没有布局包时这里只是一份纯数据表，零副作用（R3）。
 */
public final class UiEditTargets {

    private static final String TAG = "UiLayout";
    private static final Map<ResourceLocation, IUiEditTarget> TARGETS = new ConcurrentHashMap<>();

    private UiEditTargets() {
    }

    public static void register(IUiEditTarget target) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(target.id(), "target id");

        IUiEditTarget previous = TARGETS.putIfAbsent(target.id(), target);
        if (previous != null) {
            LogManager.clientWarn(TAG, "UI edit target '%s' registered twice; keeping the first registration",
                    target.id());
        }
    }

    public static @Nullable IUiEditTarget get(@Nullable ResourceLocation id) {
        return id == null ? null : TARGETS.get(id);
    }

    public static List<IUiEditTarget> getAll() {
        return TARGETS.values().stream()
                .sorted(Comparator.comparing(target -> target.id().toString()))
                .toList();
    }

    /** 这一屏上现在能拖的元素，按 id 排序（绘制与命中测试的顺序稳定）。 */
    public static List<IUiEditTarget> present(AbstractContainerScreen<?> screen) {
        return TARGETS.values().stream()
                .filter(target -> target.present(screen))
                .sorted(Comparator.comparing(target -> target.id().toString()))
                .toList();
    }
}
