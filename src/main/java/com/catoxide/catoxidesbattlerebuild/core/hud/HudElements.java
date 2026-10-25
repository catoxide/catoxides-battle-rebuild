package com.catoxide.catoxidesbattlerebuild.core.hud;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内容包 → HUD 布局系统的元素登记表（静态注册表 + 接口，同 {@code SwordAttackHandlers}）。
 *
 * <p>core 不认识任何包类；包在自己的 {@code register()} 里调用 {@link #register}，
 * 布局包从 {@link #getAll()} 读全部元素。没有布局包时这里就是一份纯数据表，零副作用（R3）。
 */
public final class HudElements {

    private static final String TAG = "HudLayout";
    private static final Map<ResourceLocation, HudElement> ELEMENTS = new ConcurrentHashMap<>();

    private HudElements() {
    }

    public static void register(HudElement element) {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(element.id(), "element id");
        Objects.requireNonNull(element.group(), "element group");

        HudElement previous = ELEMENTS.putIfAbsent(element.id(), element);
        if (previous != null) {
            LogManager.serverWarn(TAG, "HUD element '%s' registered twice; keeping the first registration",
                    element.id());
        }
    }

    public static HudElement get(ResourceLocation id) {
        return id == null ? null : ELEMENTS.get(id);
    }

    public static List<HudElement> getAll() {
        return ELEMENTS.values().stream()
                .sorted(Comparator.comparing(element -> element.id().toString()))
                .toList();
    }

    /** 元素 → 分组；未登记返回 {@code null}。 */
    public static ResourceLocation groupOf(ResourceLocation id) {
        HudElement element = get(id);
        return element == null ? null : element.group();
    }
}
