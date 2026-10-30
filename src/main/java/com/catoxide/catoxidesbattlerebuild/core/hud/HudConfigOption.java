package com.catoxide.catoxidesbattlerebuild.core.hud;

import java.util.Objects;

/**
 * 元素交给 HUD 布局包调的<b>一个配置选项</b>——只是引用，不含值、类型、区间。
 *
 * <p>为什么是引用：选项的真相在内容包的 {@code config-declarations.json} 里。若在 Java 侧
 * 再写一遍类型和区间，两份声明必然发散（改声明忘改这里，面板就显示错类型或越界写值）。
 * 布局包按 {@code packId + configId + key} 向 core 的 {@code PackConfigRegistry} 取句柄，
 * 类型、区间、默认值、生效档位、显示名全部从已编译的配置里读出来。
 *
 * <p>引用解析不到时<b>不报错</b>：目标包没装、配置声明被桥接层拒绝、键被改名——
 * 布局包跳过这一行并告警一次（同 R3 的缺席即无副作用）。
 *
 * <p>引用别人家的配置是本契约唯一的跨包用途，且布局包只经由 core 的 {@code PackConfig}
 * 读写，从不 import 目标包的类，因此不存在 {@link java.lang.ClassLoader} 私有副本问题。
 *
 * @param packId   拥有该配置的内容包 id（{@code ContentPack#getId()}）
 * @param configId 包内配置 id（{@code ContentPackContext#config(id)} 用的那个）
 * @param key      选项键（声明里的小写键名，不含分区前缀）
 */
public record HudConfigOption(String packId, String configId, String key) {

    public HudConfigOption {
        Objects.requireNonNull(packId, "packId");
        Objects.requireNonNull(configId, "configId");
        Objects.requireNonNull(key, "key");
    }

    public static HudConfigOption of(String packId, String configId, String key) {
        return new HudConfigOption(packId, configId, key);
    }
}
