package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ContentPack 注册表
 * <p>管理所有已加载的 ContentPack，提供查询、验证和生命周期管理。
 */
public final class ContentPackRegistry {

    private static final ConcurrentHashMap<String, LoadedPack> PACKS = new ConcurrentHashMap<>();
    private static volatile boolean locked = false;

    private ContentPackRegistry() {}

    /**
     * 注册一个已加载的 ContentPack
     *
     * @param pack     ContentPack 实现
     * @param jarFile  来源 JAR 文件（用于资源包注册）
     * @return true 注册成功，false 注册失败（ID 冲突或注册表已锁定）
     */
    public static boolean register(ContentPack pack, File jarFile) {
        String jarName = jarFile.getName();
        if (locked) {
            LogManager.serverWarn("ContentPack", "Cannot register pack '%s' - registry is locked", pack.getId());
            return false;
        }
        if (PACKS.containsKey(pack.getId())) {
            LogManager.serverError("ContentPack", String.format("Duplicate ContentPack ID: '%s' (source: %s)", pack.getId(), jarName));
            return false;
        }
        PACKS.put(pack.getId(), new LoadedPack(pack, jarName, jarFile));
        LogManager.serverInfo("ContentPack", "Registered ContentPack '%s' v%s from %s", pack.getId(), pack.getVersion(), jarName);
        return true;
    }

    /**
     * 锁定注册表（注册阶段结束后调用，禁止后续注册）
     */
    public static void lock() {
        locked = true;
        LogManager.serverInfo("ContentPack", "Registry locked. %d pack(s) loaded.", PACKS.size());
    }

    /**
     * 检查注册表是否已锁定
     */
    public static boolean isLocked() {
        return locked;
    }

    /**
     * 根据 ID 获取已加载的 ContentPack
     */
    public static Optional<LoadedPack> getPack(String id) {
        return Optional.ofNullable(PACKS.get(id));
    }

    /**
     * 获取所有已加载的 ContentPack
     */
    public static List<LoadedPack> getAllPacks() {
        return Collections.unmodifiableList(new ArrayList<>(PACKS.values()));
    }

    /**
     * 获取已加载的 pack 数量
     */
    public static int getPackCount() {
        return PACKS.size();
    }

    /**
     * 已加载的 ContentPack 包装
     */
    public record LoadedPack(
            ContentPack pack,
            String sourceJar,
            File jarFile,
            java.util.Date loadTime
    ) {
        public LoadedPack(ContentPack pack, String sourceJar, File jarFile) {
            this(pack, sourceJar, jarFile, new java.util.Date());
        }

        public String id() { return pack.getId(); }

        public String displayName() { return pack.getDisplayName(); }

        public String version() { return pack.getVersion(); }
    }
}
