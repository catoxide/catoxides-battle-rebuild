package com.catoxide.catoxidesbattlerebuild.core.registry;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块状态参数注册表（全局单例，基座持有）
 * <p>
 * 两级参数：
 * <ol>
 *   <li><b>标准参数</b>：来自 {@link BlockPropertyCatalog}（目录 JSON），启动时预注册；</li>
 *   <li><b>自定义参数</b>：包在自己的 {@code register()} 阶段经
 *       {@code ContentPackContext.registerBlockProperty} 注册 —— 注册后即可被同包及
 *       拓扑序上后续包的 {@code blocks/*.json} 引用，且名字进入「可选参数」全集
 *       （{@link #knownNames()}；编辑器侧的可选选项随目录文件扩充）。</li>
 * </ol>
 *
 * <p>注意：批量注册构建方块状态时<b>不依赖</b>本表 —— 它按 {@code blocks/*.json} 里的
 * 内联声明现场构建 {@link Property}（见 {@link BatchContentRegistrar}）。
 * 本表的职责是<b>发现与治理</b>：谁注册了什么参数、重名冲突检测、目录一致性日志。
 */
public final class BlockPropertyRegistry {

    private static final Map<String, Property<?>> CUSTOM_PROPERTIES = new ConcurrentHashMap<>();
    private static volatile boolean standardLogged;

    private BlockPropertyRegistry() {
    }

    /** 首次使用时预注册目录里的全部标准参数（幂等，只打一次日志） */
    public static synchronized void ensureStandardLoaded() {
        if (standardLogged) {
            return;
        }
        standardLogged = true;
        int n = 0;
        for (BlockPropertySpec spec : BlockPropertyCatalog.getSpecs()) {
            try {
                buildAndCheck(spec);
                n++;
            } catch (IllegalArgumentException e) {
                LogManager.serverError("BlockPropertyRegistry", "standard property '%s' invalid: %s",
                        spec.name(), e.getMessage());
            }
        }
        LogManager.serverInfo("BlockPropertyRegistry", "Standard block properties ready: %d (catalog) + %d custom",
                n, CUSTOM_PROPERTIES.size());
    }

    /**
     * 注册一个自定义参数（包代码路径）。
     *
     * @param spec 参数声明（类型/取值/默认值）
     * @return 构建出的 {@link Property} 实例（调用方可直接用于自己的 Block 代码）
     * @throws IllegalArgumentException 与已有参数重名（标准或自定义）或声明非法
     */
    public static synchronized Property<?> registerCustom(BlockPropertySpec spec) {
        ensureStandardLoaded();
        List<String> problems = BlockPropertyCatalog.validate(spec);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("自定义参数非法: " + String.join("; ", problems));
        }
        if (BlockPropertyCatalog.find(spec.name()).isPresent()) {
            throw new IllegalArgumentException("自定义参数 '" + spec.name() + "' 与标准参数重名");
        }
        Property<?> built = BlockPropertyCatalog.buildProperty(spec);
        Property<?> prev = CUSTOM_PROPERTIES.putIfAbsent(spec.name(), built);
        if (prev != null && prev != built) {
            throw new IllegalArgumentException("自定义参数 '" + spec.name() + "' 已被其他包注册（参数名全局唯一）");
        }
        LogManager.serverInfo("BlockPropertyRegistry", "Custom block property registered: %s (%s, values=%s)",
                spec.name(), spec.type(), spec.values());
        return built;
    }

    /** 已注册的自定义参数（含包代码注册的） */
    public static Optional<Property<?>> getCustom(String name) {
        return Optional.ofNullable(CUSTOM_PROPERTIES.get(name));
    }

    /** 自定义参数是否已注册 */
    public static boolean hasCustom(String name) {
        return CUSTOM_PROPERTIES.containsKey(name);
    }

    /**
     * 名字是否「已知」：标准（目录）或自定义（已注册）。
     * 供注册器做友好报错（引用了未定义的参数名时提示去目录/先注册）。
     */
    public static boolean isKnown(String name) {
        return BlockPropertyCatalog.find(name).isPresent() || CUSTOM_PROPERTIES.containsKey(name);
    }

    /** 全部已知参数名（标准在前，自定义按注册序追加）—— 编辑器可选选项全集的运行时投影 */
    public static List<String> knownNames() {
        List<String> names = new java.util.ArrayList<>();
        for (BlockPropertySpec spec : BlockPropertyCatalog.getSpecs()) {
            names.add(spec.name());
        }
        for (String name : CUSTOM_PROPERTIES.keySet()) {
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static void buildAndCheck(BlockPropertySpec spec) {
        BlockPropertyCatalog.buildProperty(spec);
    }
}
