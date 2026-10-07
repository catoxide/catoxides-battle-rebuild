package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import cn.solarmoon.spark_core.pack.SparkPackLoader;
import cn.solarmoon.spark_core.pack.graph.SparkPackMetaInfo;
import cn.solarmoon.spark_core.pack.graph.SparkPackage;
import cn.solarmoon.spark_core.pack.modules.SparkPackModule;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * ContentPack 加载器
 * <p>负责：
 * <ol>
 *   <li>扫描指定目录下的 .jar 文件</li>
 *   <li>解析 MANIFEST.MF 识别 ContentPack 模块</li>
 *   <li>验证版本兼容性</li>
 *   <li>通过 ClassLoader 加载模块入口类</li>
 *   <li>桥接 Spark-Core 资源加载</li>
 *   <li>注册到 ContentPackRegistry</li>
 * </ol>
 *
 * <h3>加载流程</h3>
 * <pre>
 * 1. scan()        → 发现所有 .jar 文件
 * 2. parseManifest() → 过滤出 Module-Type=contentpack 的 JAR
 * 3. verify()      → 校验版本依赖
 * 4. loadClass()   → 通过 URLClassLoader 加载入口类
 * 5. instantiate() → 实例化 ContentPack
 * 6. register()    → 注册到 ContentPackRegistry
 * 7. bridgeSpark()  → 桥接 Spark-Core 资源
 * </pre>
 */
public final class ContentPackLoader {

    private static final String TAG = "ContentPackLoader";

    /** 数据预检最多逐条打印多少个问题（剩下的折叠成一行，避免刷屏）。 */
    private static final int MAX_LOGGED_DATA_ISSUES = 20;

    private ContentPackLoader() {}

    /**
     * 外部 jar 库注册表：<库名, jar 文件>。
     * <p>从扫描目录的 {@code _libs/<库名>/} 子目录收集（或直接 {@code _libs/<库名>.jar}）。
     * 库 jar 不被打包、不被当作 contentpack 注册、不注册为 mod——仅作为类加载闭包的一部分。
     */
    private static final Map<String, File> LIBRARIES = new LinkedHashMap<>();

    public static Map<String, File> getLibraries() {
        return LIBRARIES;
    }

    /**
     * 扫描目录并加载所有 ContentPack
     * <p>在主 mod 构造函数中调用，完成整个加载流程。
     *
     * @param scanDirs    要扫描的目录列表（如 mods/ 下的 contentpacks/ 子目录）
     * @param hostVersion 主 mod 版本号（用于兼容性验证）
     * @return 成功加载的 pack 数量
     */
    public static int loadAll(List<String> scanDirs, String hostVersion) {
        LogManager.serverInfo(TAG, "=== Starting ContentPack scan ===");
        LogManager.serverInfo(TAG, "Host mod version: %s", hostVersion);

        List<ScannablePack> discovered = new ArrayList<>();
        ScanStats stats = new ScanStats();

        // Step 1: 扫描所有目录（同时收集 _libs/ 下的外部 jar 库）
        for (String dirPath : scanDirs) {
            scanDirectory(dirPath, hostVersion, discovered, stats);
        }

        if (stats.jarsScanned == 0) {
            LogManager.serverInfo(TAG, "No ContentPack JARs found in scanned directories");
            LogManager.serverInfo(TAG, "Expected directories: %s", scanDirs);
            LogManager.serverInfo(TAG, "To add ContentPacks, place .jar files with Module-Type=contentpack in these directories");
            return 0;
        }

        LogManager.serverInfo(TAG, "Discovered %d ContentPack candidate(s) from %d jar(s)",
                discovered.size(), stats.jarsScanned);

        // Step 2: 建立依赖图 + 硬依赖校验（包 → 包）。
        // 图的节点是 MANIFEST 的 Module-Name；扫描期就判死的包（版本不兼容等）先入图再标失败，
        // 于是依赖它们的包会在同一步被连带取消加载 —— 传递闭包由 ContentPackGraph 负责，
        // 不再需要这里手写固定点迭代。这一步取代了旧的「把依赖包 jar 注入类加载闭包」。
        ContentPackGraph.reset();
        for (ScannablePack p : discovered) {
            ContentPackGraph.register(p.manifest.moduleName(),
                    p.manifest.dependencyNames(), p.manifest.optionalDependencyNames());
            if (p.skipReason != null) {
                quarantine(p.manifest.moduleName(), "manifest", p.skipReason);
            }
        }

        // Step 2.5: 数据面预检 —— 必须在 data/ 挂载之前。
        //
        // 代码面失败已经有 catch(Throwable) + 隔离兜底，但 data/ 里写错的 JSON 和读不出来的
        // .nbt 不会经过任何 Java 代码路径：它们由 RegistryDataLoader 在挂载之后才读，
        // 一旦出错就是 "Failed to load registries" —— 世界根本打不开，只能进安全模式。
        // 所以在挂载前静态查一遍，把这类失败拉回与代码失败同一条隔离路径。
        //
        // 跨包索引一次建好：引用是否悬空（start_pool / location / biome tag …）需要看到所有包提供了什么。
        List<File> discoveredJars = new ArrayList<>();
        for (ScannablePack p : discovered) {
            discoveredJars.add(p.jarFile);
        }
        ContentPackDataValidator.Index dataIndex = ContentPackDataValidator.indexAll(discoveredJars);
        for (ScannablePack p : discovered) {
            if (p.skipReason != null) {
                continue;
            }
            List<ContentPackDataValidator.Issue> dataIssues =
                    ContentPackDataValidator.validate(p.jarFile, dataIndex);
            if (dataIssues.isEmpty()) {
                continue;
            }
            for (int i = 0; i < dataIssues.size() && i < MAX_LOGGED_DATA_ISSUES; i++) {
                LogManager.serverError(TAG, "  ✗ '%s' %s", p.manifest.moduleName(), dataIssues.get(i));
            }
            if (dataIssues.size() > MAX_LOGGED_DATA_ISSUES) {
                LogManager.serverError(TAG, "  ✗ '%s' ... and %d more data problem(s)",
                        p.manifest.moduleName(), dataIssues.size() - MAX_LOGGED_DATA_ISSUES);
            }
            quarantine(p.manifest.moduleName(), "data", ContentPackDataValidator.summarize(dataIssues));
        }

        applyHardDependencyChecks(discovered);

        // Step 3: 按依赖拓扑序加载并注册（保序写入 ContentPackRegistry）
        List<ScannablePack> ordered = topologicalSort(discovered);

        int loaded = 0;
        int failed = 0;
        for (ScannablePack pack : ordered) {
            if (loadAndRegister(pack, buildClasspath(pack))) {
                loaded++;
            } else {
                failed++;
                // 加载失败的包必须进隔离：否则依赖它的包会照常 register()，
                // 拿不到能力却继续注册内容 —— 那正是「东西在，但不工作」的来源。
                quarantine(pack.manifest.moduleName(), "load",
                        pack.failureReason != null ? pack.failureReason : "load failed");
            }
        }

        // Step 4: 四段式总结 —— 被跳过的包不再从统计里消失（旧实现里 skipped 恒为 0）
        int skipped = 0;
        for (ScannablePack p : discovered) {
            if (p.skipReason != null) {
                skipped++;
            }
        }

        LogManager.serverInfo(TAG, "=== ContentPack Load Summary ===");
        LogManager.serverInfo(TAG, "Scanned: %d jar(s) | Loaded: %d | Skipped: %d | Failed: %d",
                stats.jarsScanned, loaded, skipped, failed);
        if (stats.rejected > 0) {
            LogManager.serverInfo(TAG, "  %d jar(s) rejected before dependency resolution (not a ContentPack / no Module-Name)", stats.rejected);
        }
        for (ScannablePack p : discovered) {
            if (p.skipReason != null) {
                LogManager.serverWarn(TAG, "  skipped '%s': %s", p.manifest.moduleName(), p.skipReason);
            }
        }
        LogManager.serverInfo(TAG, "Total in registry: %d", ContentPackRegistry.getPackCount());

        return loaded;
    }

    /**
     * 硬依赖校验：{@code Module-Dependencies} 中任何一个包缺失，或该包自身已被跳过，
     * 则本包标记为跳过。
     *
     * <p>用固定点迭代处理依赖链：A→B→C 时若 C 被跳过，先标记 B，再在下一轮标记 A。
     */
    private static void applyHardDependencyChecks(List<ScannablePack> discovered) {
        Set<String> known = new HashSet<>();
        for (ScannablePack p : discovered) {
            known.add(p.manifest.moduleName());
        }

        // 这里只需判「依赖根本不存在」：依赖存在但已失败/已被取消的情况，
        // 已由 ContentPackGraph 沿反向边自动传递到位（含多级链路），不必再手写固定点迭代。
        for (ScannablePack p : discovered) {
            if (p.skipReason != null) {
                continue;
            }
            for (String dep : p.manifest.dependencyNames()) {
                if (!known.contains(dep)) {
                    quarantine(p.manifest.moduleName(), "manifest",
                            String.format("missing required pack '%s' (declared in Module-Dependencies)", dep));
                    break;
                }
            }
        }

        // 图里的判定回写到 ScannablePack：拓扑排序与四段式总结仍按 skipReason 工作。
        for (ScannablePack p : discovered) {
            if (p.skipReason == null && ContentPackGraph.isQuarantined(p.manifest.moduleName())) {
                p.skipReason = ContentPackGraph.reasonOf(p.manifest.moduleName());
            }
        }
    }

    /**
     * 隔离一个包：标记它失败，并沿硬依赖链连带取消所有（直接或间接）依赖它的包。
     *
     * <p>这是故障隔离的<b>唯一入口</b> —— 扫描、加载、register、init 的失败路径全走这里，
     * 保证「一个包死了，依赖它的包一起下车」这条语义在任何阶段都一致。
     *
     * <p>注意语义是<b>切断消费</b>而非回滚：NeoForge 的 DeferredRegister 没有反注册 API，
     * 所以被隔离包已注册的 EntityType 会留下（惰性残留），但不再有任何东西认领它们。
     */
    private static void quarantine(String id, String phase, String reason) {
        LogManager.serverError(TAG, "  ✗ ContentPack '%s' quarantined at %s: %s", id, phase, reason);
        for (ContentPackGraph.Fallout fallout : ContentPackGraph.fail(id, phase, reason)) {
            LogManager.serverWarn(TAG, "    ⤬ cancelling dependent ContentPack '%s': %s",
                    fallout.id(), ContentPackGraph.reasonOf(fallout.id()));
            if (fallout.wasActive()) {
                LogManager.serverWarn(TAG,
                        "      note: '%s' had already run register() - its registrations remain, but inert", fallout.id());
            }
        }
    }

    /** 异常描述，覆盖 Error 子类（如 NoClassDefFoundError —— 包少带一个类时的典型症状）。 */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** 扫描期统计（用于四段式总结）。 */
    private static final class ScanStats {
        int jarsScanned;
        int rejected;
    }

    private static void scanDirectory(String dirPath, String hostVersion,
                                      List<ScannablePack> discovered, ScanStats stats) {
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            LogManager.serverDebug(TAG, "ContentPack directory '%s' does not exist, skipping", dirPath);
            return;
        }

        File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            LogManager.serverDebug(TAG, "No .jar files found in '%s'", dirPath);
        } else {
            LogManager.serverInfo(TAG, "Scanning %d .jar file(s) in %s", jars.length, dirPath);
        }
        stats.jarsScanned += (jars == null ? 0 : jars.length);

        // 先收集 _libs/ 子目录下的外部 jar 库（不当作 contentpack，仅入类加载闭包）
        File libsDir = new File(dir, "_libs");
        if (libsDir.exists() && libsDir.isDirectory()) {
            for (File libEntry : libsDir.listFiles()) {
                if (libEntry.isDirectory()) {
                    // _libs/<库名>/xxx.jar —— 取子目录内第一个 jar
                    File[] libJars = libEntry.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
                    if (libJars != null && libJars.length > 0) {
                        LIBRARIES.put(libEntry.getName(), libJars[0]);
                        LogManager.serverInfo(TAG, "  [lib] %s -> %s", libEntry.getName(), libJars[0].getName());
                    }
                } else if (libEntry.getName().toLowerCase().endsWith(".jar")) {
                    // _libs/<库名>.jar
                    String libName = libEntry.getName().substring(0, libEntry.getName().length() - 4);
                    LIBRARIES.put(libName, libEntry);
                    LogManager.serverInfo(TAG, "  [lib] %s -> %s", libName, libEntry.getName());
                }
            }
        }

        if (jars == null || jars.length == 0) {
            return;
        }

        for (File jar : jars) {
            ContentPackManifest manifest = ContentPackManifest.fromJar(jar);
            if (manifest == null) {
                stats.rejected++;
                // Diagnostic: check if JAR has a manifest at all, and if it has required attributes
                try (JarFile testJar = new JarFile(jar)) {
                    java.util.jar.Manifest jManifest = testJar.getManifest();
                    if (jManifest == null) {
                        LogManager.serverWarn(TAG, "  ✗ '%s' has NO MANIFEST.MF - skipping", jar.getName());
                    } else {
                        String mType = jManifest.getMainAttributes().getValue("Module-Type");
                        String mName = jManifest.getMainAttributes().getValue("Module-Name");
                        String mEntry = jManifest.getMainAttributes().getValue("Module-Entry");
                        LogManager.serverWarn(TAG, "  ✗ '%s' missing ContentPack manifest attributes - skipping", jar.getName());
                        LogManager.serverWarn(TAG, "    Module-Type: %s (expected: contentpack)", mType);
                        LogManager.serverWarn(TAG, "    Module-Name: %s", mName);
                        LogManager.serverWarn(TAG, "    Module-Entry: %s", mEntry);
                        LogManager.serverWarn(TAG, "    To fix: ensure the JAR's META-INF/MANIFEST.MF contains ContentPack metadata");
                        LogManager.serverWarn(TAG, "    This often happens when IDE file sync strips the manifest during copy");
                    }
                } catch (IOException e) {
                    LogManager.serverWarn(TAG, "  ✗ '%s' cannot be read as JAR: %s - skipping",
                            jar.getName(), e.getMessage());
                }
                continue;
            }

            // 兼容性验证（主 mod 版本 + 外部 mod 硬依赖）
            String compatIssue = manifest.verifyCompatibility(hostVersion);
            if (compatIssue == null) {
                compatIssue = manifest.verifyModDependencies();
            }
            if (compatIssue != null) {
                LogManager.serverWarn(TAG, "ContentPack '%s' compatibility issue: %s",
                        manifest.moduleName(), compatIssue);
                discovered.add(new ScannablePack(jar, manifest, compatIssue));
                continue;
            }

            // 可选 mod 缺失：仅警告，不阻止加载（软依赖）
            for (String missing : manifest.missingOptionalMods()) {
                LogManager.serverWarn(TAG, "ContentPack '%s': optional mod '%s' not installed (continuing)",
                        manifest.moduleName(), missing);
            }

            // 重名检测：Module-Name 是依赖图的键，重名会让依赖解析产生歧义 → 拒绝后来者
            ScannablePack duplicate = findByModuleName(discovered, manifest.moduleName());
            if (duplicate != null) {
                stats.rejected++;
                LogManager.serverError(TAG, "Duplicate ContentPack Module-Name '%s': ignoring '%s' (already provided by '%s')",
                        manifest.moduleName(), jar.getName(), duplicate.jarFile.getName());
                continue;
            }

            LogManager.serverInfo(TAG, "  ✓ '%s' v%s passes compatibility check",
                    manifest.moduleName(), manifest.moduleVersion());
            discovered.add(new ScannablePack(jar, manifest, null));
        }
    }

    /** 在已发现列表中按 Module-Name 查找。 */
    private static ScannablePack findByModuleName(List<ScannablePack> discovered, String moduleName) {
        for (ScannablePack p : discovered) {
            if (p.manifest.moduleName().equals(moduleName)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 加载并注册单个 ContentPack。
     *
     * @param pack      扫描到的包
     * @param classpath 类加载闭包：自身 jar + 本包声明的 {@code _libs} 库（<b>不含</b>依赖包的 jar）
     */
    /**
     * 加载并注册单个 ContentPack，并保证它<b>永远不会把异常抛给调用方</b>。
     *
     * <p>这里刻意 catch {@link Throwable}：只 catch {@code Exception} 的话，
     * 包少带一个类（{@code NoClassDefFoundError}）或静态初始化炸掉
     * （{@code ExceptionInInitializerError}）会直接终止整个加载循环 —— 游戏打不开。
     * 而 {@link VirtualMachineError}（OOM / StackOverflow）必须放行：那不是包的错，
     * 吞掉只会让后续状态更乱。
     */
    private static boolean loadAndRegister(ScannablePack pack, List<File> classpath) {
        try {
            return doLoadAndRegister(pack, classpath);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) {
                throw vme;
            }
            pack.failureReason = describe(t);
            LogManager.serverError(TAG, String.format("Unhandled error while loading ContentPack '%s': %s",
                    pack.manifest.moduleName(), describe(t)), t);
            return false;
        }
    }

    private static boolean doLoadAndRegister(ScannablePack pack, List<File> classpath) {
        File jar = pack.jarFile;
        ContentPackManifest manifest = pack.manifest;

        // 纯数据包（无 Module-Entry）：没有类要加载。构造一个虚拟 ContentPack，
        // 使其 assets/ 与 data/ 仍能挂载 —— 否则规范承诺的「L0/L1 免代码能力」是断的。
        if (manifest.moduleEntry() == null) {
            ContentPack dataOnly = new DataOnlyContentPack(manifest.moduleName(), manifest.moduleVersion());
            bridgeSparkResources(jar, manifest.moduleName());
            if (!ContentPackRegistry.register(dataOnly, jar)) {
                LogManager.serverError(TAG, "Failed to register data-only ContentPack '%s'", manifest.moduleName());
                return false;
            }
            LogManager.serverInfo(TAG, "  ✓ Registered pure data ContentPack '%s' (no code, resources only)",
                    manifest.moduleName());
            return true;
        }

        LogManager.serverInfo(TAG, "Loading ContentPack: '%s' v%s", manifest.moduleName(), manifest.moduleVersion());

        // Step 1: 创建类加载器（自身 jar + 声明的 _libs 库；黑名单 parent-only）
        URLClassLoader classLoader;
        try {
            classLoader = ChildFirstClassLoader.of(classpath, ContentPackManifest.class.getClassLoader());
        } catch (Exception e) {
            LogManager.serverError(TAG, String.format("Failed to create ClassLoader for '%s': %s",
                    manifest.moduleName(), e.getMessage()));
            return false;
        }

        // Step 2: 加载入口类
        Class<?> entryClass;
        try {
            entryClass = classLoader.loadClass(manifest.moduleEntry());
        } catch (ClassNotFoundException e) {
            LogManager.serverError(TAG, String.format("Entry class '%s' not found in '%s'",
                    manifest.moduleEntry(), jar.getName()));
            return false;
        } catch (Exception e) {
            LogManager.serverError(TAG, String.format("Failed to load entry class '%s' from '%s': %s",
                    manifest.moduleEntry(), jar.getName(), e.getMessage()));
            return false;
        }

        // Step 3: 验证 ContentPack 接口
        if (!ContentPack.class.isAssignableFrom(entryClass)) {
            LogManager.serverError(TAG, String.format("Entry class '%s' does not implement ContentPack interface",
                    manifest.moduleEntry()));
            return false;
        }

        // Step 4: 实例化 ContentPack
        ContentPack contentPack;
        try {
            contentPack = (ContentPack) entryClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            LogManager.serverError(TAG, String.format("Failed to instantiate ContentPack '%s': %s",
                    manifest.moduleName(), e.getMessage()), e);
            return false;
        }

        // Step 5: 桥接 Spark-Core 资源
        bridgeSparkResources(jar, contentPack.getId());

        // Step 6: 注册到 ContentPackRegistry
        if (!ContentPackRegistry.register(contentPack, jar)) {
            LogManager.serverError(TAG, String.format("Failed to register ContentPack '%s'", contentPack.getId()));
            return false;
        }

        // 依赖图以 Module-Name 为键，注册表以 getId() 为键。两者不一致时隔离会漏掉人
        // （标记失败的是图里那个名字，消费方查的却是另一个），所以这里主动报警。
        if (!contentPack.getId().equals(manifest.moduleName())) {
            LogManager.serverWarn(TAG,
                    "  ⚠ id mismatch: getId()='%s' but Manifest Module-Name='%s' (%s) - the dependency graph is keyed by Module-Name, keep both identical",
                    contentPack.getId(), manifest.moduleName(), jar.getName());
        }

        LogManager.serverInfo(TAG, "  ✓ Successfully loaded ContentPack '%s'", contentPack.getId());
        return true;
    }

    /**
     * 构建本包的<b>类加载闭包</b>：自身 jar + {@code Module-Lib-Requires} 声明的外部 jar 库。
     *
     * <p><b>不再包含依赖包的 jar。</b>旧实现（{@code computeClosure}）把 {@code Module-Dependencies}
     * 中所有可达依赖包的 jar 一并注入，好让包 A 能「看到」包 B 的类 —— 但那会让 B 的类在 A 的
     * loader 里再加载一份，与 B 自己那份是<b>不同的 Class 对象</b>，于是 B 的静态状态静默发散。
     * 跨包功能传递一律走 {@link Capability} / {@link CapabilityRegistry}（接口住 core，全局单例）。
     *
     * <p>保留 {@code _libs} 库注入：那是纯类库，不存在「属于某个包」的静态状态，安全。
     */
    private static List<File> buildClasspath(ScannablePack pack) {
        List<File> classpath = new ArrayList<>();
        classpath.add(pack.jarFile);

        for (String libName : pack.manifest.libNames()) {
            File libJar = LIBRARIES.get(libName);
            if (libJar != null) {
                classpath.add(libJar);
                LogManager.serverInfo(TAG, "ContentPack '%s' linked library jar: %s -> %s",
                        pack.manifest.moduleName(), libName, libJar.getName());
            } else {
                LogManager.serverWarn(TAG, "ContentPack '%s' declares library '%s' but no matching jar found in _libs/ (skip)",
                        pack.manifest.moduleName(), libName);
            }
        }
        return classpath;
    }

    /**
     * 拓扑排序（DFS）：被依赖者排在依赖者之前。
     *
     * <p><b>这个顺序现在真的有用了</b>：{@link ContentPackRegistry} 保序存储，
     * 因此 {@code getAllPacks()} 的返回顺序就是这里算出的顺序，
     * 于是 {@code register()} 的调用顺序 = 拓扑序，包 A 能确定地看到已声明依赖的包已注册。
     *
     * <ul>
     *   <li>已标记跳过的包不参与依赖图（也不会被加载）</li>
     *   <li>软依赖（{@code Module-Dependencies-Optional}）<b>不参与排序</b>：它只表达「在就启用」</li>
     *   <li>循环依赖 → 警告 + 尽力排序（两阶段 register/init 兜底）</li>
     * </ul>
     *
     * <p>缺失的硬依赖到不了这里 —— {@link #applyHardDependencyChecks} 已把依赖者标记为跳过。
     * <p>用 {@link LinkedHashMap} 保证基序稳定（发现顺序），使排序结果可复现、日志可比对。
     */
    private static List<ScannablePack> topologicalSort(List<ScannablePack> packs) {
        Map<String, ScannablePack> byName = new LinkedHashMap<>();
        for (ScannablePack p : packs) {
            if (p.skipReason != null) {
                continue; // 跳过/等不到的包不参与依赖图（反正不会被加载）
            }
            byName.put(p.manifest.moduleName(), p);
        }

        // 第二遍构建依赖集（确保 byName 完整后再判断 containsKey）
        Map<String, Set<String>> depSet = new LinkedHashMap<>();
        for (ScannablePack p : packs) {
            if (p.skipReason != null) {
                continue;
            }
            Set<String> deps = new LinkedHashSet<>();
            for (String dep : p.manifest.dependencyNames()) {
                if (byName.containsKey(dep)) {
                    deps.add(dep);
                } else {
                    LogManager.serverWarn(TAG, "ContentPack '%s' declares hard dependency '%s' which is not loadable (ignoring edge)",
                            p.manifest.moduleName(), dep);
                }
            }
            depSet.put(p.manifest.moduleName(), deps);
        }

        List<ScannablePack> result = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> inProgress = new HashSet<>();
        for (String name : byName.keySet()) {
            dfsTopo(name, byName, depSet, done, inProgress, result);
        }
        return result;
    }

    /** DFS 拓扑排序（递归）；检测到环时警告并跳过该分支（尽力排序） */
    private static void dfsTopo(String name,
                                Map<String, ScannablePack> byName,
                                Map<String, Set<String>> depSet,
                                Set<String> done,
                                Set<String> inProgress,
                                List<ScannablePack> result) {
        if (done.contains(name)) {
            return;
        }
        if (inProgress.contains(name)) {
            LogManager.serverWarn(TAG, "Circular dependency detected involving ContentPack '%s'; order will be best-effort", name);
            return;
        }
        inProgress.add(name);
        for (String dep : depSet.getOrDefault(name, Set.of())) {
            dfsTopo(dep, byName, depSet, done, inProgress, result);
        }
        inProgress.remove(name);
        done.add(name);
        ScannablePack pack = byName.get(name);
        if (pack != null) {
            result.add(pack);
        }
    }

    private static final List<SparkPackage> pendingSparkPackages = new ArrayList<>();

    /**
     * 桥接 Spark-Core 资源加载
     * <p>将 ContentPack JAR 中的 spark_models/ 和 spark_animations/ 目录
     * 转换为 Spark-Core 的模块格式，并注册到 SparkPackLoader。
     *
     * <p>路径转换：
     * - spark_models/entity/&lt;ns&gt;/&lt;model&gt;.json → models/&lt;ns&gt;/&lt;model&gt;.json
     * - spark_animations/entity/&lt;ns&gt;/&lt;model&gt;/&lt;anim&gt;.json → animations/&lt;ns&gt;/&lt;model&gt;/&lt;anim&gt;.json
     */
    private static void bridgeSparkResources(File jarFile, String packId) {
        try (JarFile jar = new JarFile(jarFile)) {
            Map<String, Map<String, byte[]>> sparkEntries = new LinkedHashMap<>();
            Map<String, byte[]> modelEntries = new LinkedHashMap<>();
            Map<String, byte[]> animEntries = new LinkedHashMap<>();

            // 扫描 spark_models
            List<String> models = ContentPackManifest.listResources(jar, "spark_models/");
            for (String modelPath : models) {
                if (modelPath.endsWith(".json")) {
                    String sparkPath = modelPath.replace("spark_models/", "");
                    String[] parts = sparkPath.split("/");
                    if (parts.length >= 3) {
                        String type = parts[0];
                        String ns = parts[1];
                        String fileName = parts[parts.length - 1];
                        String targetPath = ns + "/" + type + "/" + fileName;
                        byte[] content = jar.getInputStream(jar.getEntry(modelPath)).readAllBytes();
                        modelEntries.put(targetPath, content);
                    }
                }
            }

            // 扫描 spark_animations
            List<String> animations = ContentPackManifest.listResources(jar, "spark_animations/");
            for (String animPath : animations) {
                if (animPath.endsWith(".json")) {
                    String sparkPath = animPath.replace("spark_animations/", "");
                    String[] parts = sparkPath.split("/");
                    if (parts.length >= 4) {
                        String type = parts[0];
                        String ns = parts[1];
                        String modelName = parts[2];
                        String fileName = parts[parts.length - 1];
                        String targetPath = ns + "/" + type + "/" + modelName + "/" + fileName;
                        byte[] content = jar.getInputStream(jar.getEntry(animPath)).readAllBytes();
                        animEntries.put(targetPath, content);
                    }
                }
            }

            if (!modelEntries.isEmpty()) {
                sparkEntries.put("models", modelEntries);
                LogManager.serverInfo(TAG, "    Spark models in '%s': %d file(s)", packId, modelEntries.size());
            }

            if (!animEntries.isEmpty()) {
                sparkEntries.put("animations", animEntries);
                LogManager.serverInfo(TAG, "    Spark animations in '%s': %d file(s)", packId, animEntries.size());
            }

            if (!sparkEntries.isEmpty()) {
                ResourceLocation packIdLoc = ResourceLocation.fromNamespaceAndPath(CatoxidesBattleRebuildConstants.MODID, packId);
                SparkPackMetaInfo meta = new SparkPackMetaInfo(
                        packIdLoc,
                        "1.0",
                        Component.literal("ContentPack " + packId),
                        Component.literal("ContentPack"),
                        Component.literal("Auto-generated from ContentPack JAR"),
                        List.of()
                );

                SparkPackage sparkPackage = new SparkPackage(meta, sparkEntries);
                pendingSparkPackages.add(sparkPackage);
                LogManager.serverInfo(TAG, "    Spark-Core package created for '%s'", packId);
            } else {
                LogManager.serverWarn(TAG, "ContentPack '%s' contains no spark_models/ or spark_animations/ resources", packId);
                LogManager.serverWarn(TAG, "Expected structure: spark_models/entity/<ns>/<entity>.json");
            }
        } catch (Exception e) {
            LogManager.serverWarn(TAG, "Failed to bridge Spark resources for ContentPack '%s': %s", packId, e.getMessage());
        }
    }

    /**
     * 在 Spark-Core 初始化后调用，将缓存的资源包加载到 Spark-Core
     * <p>
     * 客户端：调用 readPackageContent() 完整加载（包括纹理，在 Render 线程上安全）。
     * <p>
     * 服务端：不调用 readPackageContent()（因为 TextureModule 会在服务端线程创建 DynamicTexture 导致崩溃），
     * 而是直接调用 ModelModule.read() 和 AnimationModule.read() 将模型和动画注册到 ORIGINS。
     * 同时将包加入 graph 供 collectRemote() 同步给客户端。
     */
    public static void loadPendingSparkPackages(boolean isClientSide) {
        if (pendingSparkPackages.isEmpty()) {
            LogManager.serverInfo(TAG, "No pending Spark-Core packages to load");
            return;
        }

        LogManager.serverInfo(TAG, "Loading %d pending Spark-Core package(s)", pendingSparkPackages.size());

        // 将包加入 graph（客户端供 readPackageContent 使用，服务端供 collectRemote 同步使用）
        // 已被隔离的包跳过：它的模型/动画引用的是不再生效的内容，加载进来只会制造错配。
        for (SparkPackage pack : pendingSparkPackages) {
            ResourceLocation sparkId = pack.getMeta().getId();
            if (sparkId != null && ContentPackGraph.isQuarantined(sparkId.getPath())) {
                LogManager.serverWarn(TAG, "  Skipping Spark resources of cancelled ContentPack '%s'", sparkId.getPath());
                continue;
            }
            try {
                SparkPackLoader.INSTANCE.getGraph().addNode(pack);
                LogManager.serverInfo(TAG, "  Added Spark package: %s", pack.getMeta().getId());
            } catch (Exception e) {
                LogManager.serverError(TAG, "Failed to add Spark package: " + pack.getMeta().getId(), e);
            }
        }

        if (isClientSide) {
            // 客户端：安全地调用 readPackageContent（在 Render 线程上执行）
            try {
                SparkPackLoader.INSTANCE.initialize(isClientSide);
                SparkPackLoader.INSTANCE.readPackageContent(isClientSide);
                LogManager.serverInfo(TAG, "Successfully loaded all pending Spark-Core packages (client)");
            } catch (Exception e) {
                LogManager.serverError(TAG, "Failed to read Spark package content", e);
            }
        } else {
            // 服务端：直接调用 ModelModule.read() 和 AnimationModule.read()，
            // 绕过 TextureModule（避免在服务端线程创建 DynamicTexture 导致崩溃）
            try {
                SparkPackLoader.INSTANCE.initialize(isClientSide);
                Map<String, ? extends SparkPackModule> modules = SparkPackLoader.INSTANCE.getModules();
                SparkPackModule modelModule = modules.get("models");
                SparkPackModule animModule = modules.get("animations");

                int modelCount = 0;
                int animCount = 0;

                for (SparkPackage pack : pendingSparkPackages) {
                    ResourceLocation sparkId = pack.getMeta().getId();
                    if (sparkId != null && ContentPackGraph.isQuarantined(sparkId.getPath())) {
                        continue;
                    }
                    // 注册模型
                    if (modelModule != null && pack.getEntries().get("models") != null) {
                        for (Map.Entry<String, byte[]> entry : pack.getEntries().get("models").entrySet()) {
                            String path = entry.getKey();
                            String[] parts = path.split("/");
                            String fileName = parts[parts.length - 1];
                            List<String> pathSegments = Arrays.asList(Arrays.copyOf(parts, parts.length - 1));
                            try {
                                modelModule.read(pathSegments, fileName, entry.getValue(), pack, false);
                                modelCount++;
                            } catch (Exception e) {
                                LogManager.serverError(TAG, "Failed to read model: " + path, e);
                            }
                        }
                    }
                    // 注册动画
                    if (animModule != null && pack.getEntries().get("animations") != null) {
                        for (Map.Entry<String, byte[]> entry : pack.getEntries().get("animations").entrySet()) {
                            String path = entry.getKey();
                            String[] parts = path.split("/");
                            String fileName = parts[parts.length - 1];
                            List<String> pathSegments = Arrays.asList(Arrays.copyOf(parts, parts.length - 1));
                            try {
                                animModule.read(pathSegments, fileName, entry.getValue(), pack, false);
                                animCount++;
                            } catch (Exception e) {
                                LogManager.serverError(TAG, "Failed to read animation: " + path, e);
                            }
                        }
                    }
                }
                LogManager.serverInfo(TAG, "Successfully registered %d model(s) and %d animation(s) on server (bypassing TextureModule)", modelCount, animCount);
            } catch (Exception e) {
                LogManager.serverError(TAG, "Failed to register ContentPack data on server", e);
            }
        }

        // 不清除 pendingSparkPackages，因为单机模式下服务端也需要加载这些包。
    }

    /**
     * 在主 mod 构造完成后调用，执行所有 ContentPack 的 register() 和 init()。
     *
     * <p>两阶段：先全部 {@code register()}（按<b>拓扑序</b>，见 {@link ContentPackRegistry}），
     * 再锁注册表，最后全部 {@code init()}。
     *
     * <p>{@code register()} 阶段会为每个包设置 {@code context} 的「当前包」作用域，
     * 使 {@code ctx.provide(...)} 能记录到正确的提供者 id。
     *
     * <h4>故障隔离（KubeJS 式）</h4>
     * 任何包的 {@code register()} / {@code init()} 抛错都<b>不会</b>阻止游戏启动：
     * <ol>
     *   <li>该包被隔离（回收它已提供的能力，见 {@link CapabilityRegistry#revokeAllFrom}）</li>
     *   <li>沿硬依赖链连带取消所有依赖它的包（{@link ContentPackGraph}）</li>
     *   <li>被取消的包不再执行后续阶段 —— 因为它拿不到依赖的能力，
     *       硬跑只会把「东西在，但不工作」的僵尸内容注册进游戏</li>
     * </ol>
     * 拓扑序是这一切的前提：失败包的依赖者必然排在它后面，所以隔离总能赶在它们之前生效。
     * 循环依赖会让拓扑序退化为尽力排序，那时隔离可能来晚一步（日志会标注）。
     */
    public static void executeAllRegistries(ContentPackContext context) {
        LogManager.serverInfo(TAG, "Executing registries for %d ContentPack(s)", ContentPackRegistry.getPackCount());

        // 阶段 1：register（拓扑序 —— 被依赖者先）
        for (ContentPackRegistry.LoadedPack loaded : ContentPackRegistry.getAllPacks()) {
            String id = loaded.id();
            if (ContentPackGraph.isQuarantined(id)) {
                LogManager.serverWarn(TAG, "  Skipping register() for '%s' (cancelled: %s)",
                        id, ContentPackGraph.reasonOf(id));
                continue;
            }
            try {
                LogManager.serverInfo(TAG, "  Registering: '%s'", id);
                context.beginPack(id);
                loaded.pack().register(context);
                ContentPackGraph.activate(id);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) {
                    throw vme;
                }
                // 先回收它已经 provide() 出去的能力：否则依赖者会拿到半初始化的实现当好的用
                CapabilityRegistry.revokeAllFrom(id);
                LogManager.serverError(TAG, String.format("Failed to register ContentPack '%s': %s",
                        id, describe(t)), t);
                quarantine(id, "register", describe(t));
            } finally {
                context.endPack();
            }
        }

        // 阶段 2：锁定（此后不可再注册包 / 不可再提供能力）
        ContentPackRegistry.lock();
        CapabilityRegistry.lock();

        // 阶段 3：init（只读阶段）
        for (ContentPackRegistry.LoadedPack loaded : ContentPackRegistry.getAllPacks()) {
            String id = loaded.id();
            if (ContentPackGraph.isQuarantined(id)) {
                continue;
            }
            try {
                loaded.pack().init();
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) {
                    throw vme;
                }
                LogManager.serverError(TAG, String.format("Failed to init ContentPack '%s': %s",
                        id, describe(t)), t);
                // init 阶段注册表已锁定，能力无法回收（revokeAllFrom 会拒绝）；
                // 但依赖者还没 init（拓扑序），仍然救得回来。
                quarantine(id, "init", describe(t));
            }
        }

        // 阶段 4：隔离总结 —— 让「为什么某个包没生效」在日志里一眼可见，
        // 而不是让玩家对着一个半死不活的包猜。
        List<ContentPackGraph.Snapshot> quarantined = ContentPackGraph.quarantined();
        if (quarantined.isEmpty()) {
            LogManager.serverInfo(TAG, "All ContentPack registries executed");
        } else {
            LogManager.serverWarn(TAG, "All ContentPack registries executed, but %d pack(s) were cancelled:",
                    quarantined.size());
            for (ContentPackGraph.Snapshot snapshot : quarantined) {
                LogManager.serverWarn(TAG, "  [%s] '%s' (%s): %s",
                        snapshot.status(), snapshot.id(), snapshot.phase(), snapshot.reason());
            }
            LogManager.serverWarn(TAG, "  The game keeps running; no other pack was blocked.");
        }
    }

    /**
     * 扫描结果包装。
     * <p>{@code skipReason} 可变：硬依赖校验发生在扫描<b>之后</b>，需要在此追加跳过原因。
     */
    private static final class ScannablePack {
        final File jarFile;
        final ContentPackManifest manifest;
        String skipReason;
        /** 加载阶段失败原因，由 {@link #loadAndRegister} 回填，供隔离日志说明依赖者为什么也下车。 */
        String failureReason;

        ScannablePack(File jarFile, ContentPackManifest manifest, String skipReason) {
            this.jarFile = jarFile;
            this.manifest = manifest;
            this.skipReason = skipReason;
        }
    }

    /**
     * 纯数据包的虚拟 {@link ContentPack} 实现（MANIFEST 中无 {@code Module-Entry}）。
     *
     * <p>存在的唯一目的：让这样的 JAR 也能进入 {@link ContentPackRegistry}，
     * 从而被 {@link ContentPackRepositorySource} 挂载 {@code assets/} 与 {@code data/}。
     * 它没有任何代码——{@code register()} 是空实现。
     *
     * <p>本类定义在主 mod（parent-only 前缀）中，不随包加载，因此不存在类身份问题。
     */
    private static final class DataOnlyContentPack implements ContentPack {

        private final String id;
        private final String version;

        DataOnlyContentPack(String id, String version) {
            this.id = id;
            this.version = version;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return id;
        }

        @Override
        public String getVersion() {
            return version;
        }

        @Override
        public void register(ContentPackContext context) {
            // 纯数据包：无代码可注册。资源由 ContentPackRepositorySource 挂载。
        }
    }
}
