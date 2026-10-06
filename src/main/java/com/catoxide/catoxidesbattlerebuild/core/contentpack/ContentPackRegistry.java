package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ContentPack 注册表
 * <p>管理所有已加载的 ContentPack，提供查询、验证和生命周期管理。
 *
 * <h3>顺序保证（重要）</h3>
 * 内部使用 {@link LinkedHashMap} 保留<b>插入顺序</b>。加载器按依赖拓扑序调用
 * {@link #register}，因此 {@link #getAllPacks()} 返回的顺序就是拓扑序——
 * 被依赖的包一定排在依赖它的包之前。
 *
 * <p>这直接决定了 {@code executeAllRegistries} 中 {@code register()} 的调用顺序：
 * 包 A 声明依赖包 B 时，B 的 {@code register()} 一定先于 A 执行，
 * 所以 A 能在自己的 {@code register()} 里安全地消费 B 通过
 * {@link CapabilityRegistry} 提供的能力。
 *
 * <p><b>历史</b>：此处原为 {@code ConcurrentHashMap}，其 {@code values()} 为哈希桶序，
 * 导致加载器辛苦算出的拓扑排序在执行阶段被丢弃（包间顺序实际是随机的）。
 */
public final class ContentPackRegistry {

    /** 保序表：插入顺序 == 拓扑序。所有访问方法均 synchronized。 */
    private static final Map<String, LoadedPack> PACKS = new LinkedHashMap<>();
    private static volatile boolean locked = false;

    private ContentPackRegistry() {}

    /**
     * 注册一个已加载的 ContentPack
     *
     * @param pack     ContentPack 实现
     * @param jarFile  来源 JAR 文件（用于资源包注册）
     * @return true 注册成功，false 注册失败（ID 冲突或注册表已锁定）
     */
    public static synchronized boolean register(ContentPack pack, File jarFile) {
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
    public static synchronized void lock() {
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
    public static synchronized Optional<LoadedPack> getPack(String id) {
        return Optional.ofNullable(PACKS.get(id));
    }

    /**
     * 获取所有已加载的 ContentPack，<b>按拓扑序</b>（被依赖者在前）。
     * <p>调用方可以依赖此顺序：排在后面的包一定可以看到前面所有包已注册的内容与能力。
     */
    public static synchronized List<LoadedPack> getAllPacks() {
        return Collections.unmodifiableList(new ArrayList<>(PACKS.values()));
    }

    /**
     * 获取已加载的 pack 数量
     */
    public static synchronized int getPackCount() {
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
