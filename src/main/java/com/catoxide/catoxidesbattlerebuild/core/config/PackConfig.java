package com.catoxide.catoxidesbattlerebuild.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内容包侧的配置句柄：按选项键读值，不暴露任何 FML 类型。
 *
 * <p>每次读取都走 {@link ModConfigSpec.ConfigValue#get()}（内部有缓存），因此：
 * <ul>
 *   <li>HOT 选项：重载后立刻读到新值</li>
 *   <li>WARM 选项：退出/重进世界后才读到新值</li>
 *   <li>COLD 选项：重启游戏后才读到新值</li>
 * </ul>
 * 不要在包里再缓存一份——那会把 HOT 降级成 COLD。
 */
public final class PackConfig {

    private final String packId;
    private final String id;
    private final String fileName;
    private final Map<String, ModConfigSpec.ConfigValue<?>> values;

    PackConfig(String packId, String id, String fileName, Map<String, ModConfigSpec.ConfigValue<?>> values) {
        this.packId = packId;
        this.id = id;
        this.fileName = fileName;
        this.values = values;
    }

    public String id() {
        return id;
    }

    public String fileName() {
        return fileName;
    }

    public boolean getBoolean(String key) {
        return require(key, Boolean.class);
    }

    public int getInt(String key) {
        return require(key, Integer.class);
    }

    public double getDouble(String key) {
        return require(key, Double.class);
    }

    public String getString(String key) {
        return require(key, String.class);
    }

    public List<String> getStringList(String key) {
        Object raw = raw(key);
        if (!(raw instanceof List<?> list)) {
            throw typeError(key, "a list of strings", raw);
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String text)) {
                throw typeError(key, "a list of strings", raw);
            }
            out.add(text);
        }
        return List.copyOf(out);
    }

    private <T> T require(String key, Class<T> type) {
        Object raw = raw(key);
        if (!type.isInstance(raw)) {
            throw typeError(key, type.getSimpleName(), raw);
        }
        return type.cast(raw);
    }

    private Object raw(String key) {
        ModConfigSpec.ConfigValue<?> value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Unknown config key '" + key + "' in '" + packId + "/" + id
                    + "'; declared keys: " + values.keySet());
        }
        // 配置尚未加载时 get() 自身会抛出带说明的 IllegalStateException
        return value.get();
    }

    private IllegalStateException typeError(String key, String expected, Object actual) {
        return new IllegalStateException("Config key '" + key + "' in '" + packId + "/" + id
                + "' is not " + expected + " (got " + (actual == null ? "null" : actual.getClass().getSimpleName()) + ")");
    }
}
