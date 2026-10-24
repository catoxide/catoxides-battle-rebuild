package com.catoxide.catoxidesbattlerebuild.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 已注册配置的静态注册表（与 core 的其它「门」一致：静态表 + 查询，不用 capability）。
 * <p>key = {@code <packId>/<configId>}。包代码通过 {@code ContentPackContext#config(id)}
 * 查自己的配置，不跨包。
 */
public final class PackConfigRegistry {

    private static final Map<String, PackConfig> CONFIGS = new HashMap<>();
    /** TOML 文件名 → 拥有者，防止两个包注册到同一个文件互相覆盖。 */
    private static final Map<String, String> FILE_OWNERS = new HashMap<>();

    private PackConfigRegistry() {
    }

    /** @return true 表示文件名可用（尚未被占用） */
    public static synchronized boolean claimFile(String fileName, String owner) {
        String existing = FILE_OWNERS.putIfAbsent(fileName, owner);
        return existing == null || existing.equals(owner);
    }

    /** 登记一个已编译的配置（由内容包加载链调用，包代码不调用）。 */
    public static synchronized PackConfig install(String packId, String configId, String fileName,
                                                  Map<String, ModConfigSpec.ConfigValue<?>> values) {
        PackConfig config = new PackConfig(packId, configId, fileName, values);
        CONFIGS.put(key(packId, configId), config);
        return config;
    }

    public static synchronized Optional<PackConfig> get(String packId, String configId) {
        return Optional.ofNullable(CONFIGS.get(key(packId, configId)));
    }

    private static String key(String packId, String configId) {
        return packId + "/" + configId;
    }
}
