package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ContentPack MANIFEST.MF 解析器
 * <p>从 JAR 的 META-INF/MANIFEST.MF 中读取模块元数据：
 * <pre>
 * Module-Type: contentpack                        (必填, 固定值)
 * Module-Name: custom-zombies                     (必填)
 * Module-Version: 1.0.0                           (可选, 缺省 0.0.0)
 * Module-Requires: catoxidesbattlerebuild&gt;=1.0.0  (可选, 主 mod 契约版本)
 * Module-Entry: com.example.mypack.CustomZombiesPack   (可选! 缺省 = 纯数据包)
 * Module-Dependencies: mission-core, gun-framework     (可选, 硬依赖的包; 缺失则跳过本包)
 * Module-Dependencies-Optional: cosmetics-pack         (可选, 软依赖的包; 缺失仅警告)
 * Module-Lib-Requires: jade, spark-core                (可选, _libs/ 下的外部 jar 库)
 * Module-Mod-Requires: sable&gt;=2.0.3                    (可选, 硬依赖的外部 mod, 缺失则跳过本包)
 * Module-Mod-Optional: lso&gt;=2.4.5                      (可选, 软依赖的外部 mod, 缺失仅警告)
 * </pre>
 *
 * <h3>两条依赖轴的语义区别（重要）</h3>
 * <table border="1">
 *   <tr><th>属性</th><th>轴</th><th>作用</th><th>是否共享类空间</th></tr>
 *   <tr><td>{@code Module-Dependencies}</td><td>包 → 包</td>
 *       <td><b>只影响顺序、存在性与可选性</b>：保证被依赖包先 {@code register()}，
 *           从而其能力已可通过 {@link CapabilityRegistry} 消费</td><td><b>否</b>（从不注入 jar）</td></tr>
 *   <tr><td>{@code Module-Requires}</td><td>包 → 主 mod</td>
 *       <td>契约版本校验（主 mod API 是否满足本包）</td><td>—（主 mod 本就是 parent）</td></tr>
 *   <tr><td>{@code Module-Lib-Requires}</td><td>包 → 外部纯类库</td>
 *       <td>把 {@code _libs/<库名>/} 下的 jar 加入本包类加载闭包（无状态双份问题，安全）</td>
 *       <td>是（仅限纯类库）</td></tr>
 * </table>
 *
 * <p><b>包与包之间禁止版本约束</b>：{@code Module-Dependencies} 里不允许写
 * {@code >=} 之类的版本区间（写了会被忽略并警告）。包间契约随主 mod 的契约版本走，
 * 否则版本图会从 O(n) 星型退化为 O(n²) 网。
 */
public record ContentPackManifest(
        String moduleName,
        String moduleVersion,
        String moduleRequires,
        String moduleEntry,
        String moduleDependencies,
        String moduleDependenciesOptional,
        String moduleLibRequires,
        String moduleModRequires,
        String moduleModOptional
) {

    private static final String ATTR_NAME = "Module-Name";
    private static final String ATTR_VERSION = "Module-Version";
    private static final String ATTR_REQUIRES = "Module-Requires";
    private static final String ATTR_TYPE = "Module-Type";
    private static final String ATTR_ENTRY = "Module-Entry";
    private static final String ATTR_DEPENDENCIES = "Module-Dependencies";
    private static final String ATTR_DEPENDENCIES_OPTIONAL = "Module-Dependencies-Optional";
    private static final String ATTR_LIB_REQUIRES = "Module-Lib-Requires";
    private static final String ATTR_MOD_REQUIRES = "Module-Mod-Requires";
    private static final String ATTR_MOD_OPTIONAL = "Module-Mod-Optional";
    private static final String EXPECTED_TYPE = "contentpack";

    /**
     * 从 JAR 文件解析 ContentPackManifest
     *
     * @param jarFile JAR 文件
     * @return 解析结果，null 表示不是一个有效的 ContentPack JAR
     */
    public static ContentPackManifest fromJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return null;
            }
            return parseAttributes(manifest.getMainAttributes(), jarFile.getName());
        } catch (IOException e) {
            LogManager.serverWarn("ContentPack", "Failed to read MANIFEST from %s: %s", jarFile.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 从已打开的 JarFile 中解析（避免重复打开）
     */
    public static ContentPackManifest fromJarFile(JarFile jarFile, String jarName) {
        try {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) return null;
            return parseAttributes(manifest.getMainAttributes(), jarName);
        } catch (IOException e) {
            LogManager.serverWarn("ContentPack", "Failed to read MANIFEST from %s: %s", jarName, e.getMessage());
            return null;
        }
    }

    private static ContentPackManifest parseAttributes(Attributes attrs, String jarName) {
        // Module-Type 必须为 contentpack
        String type = attrs.getValue(ATTR_TYPE);
        if (!EXPECTED_TYPE.equals(type)) {
            return null;
        }

        String name = attrs.getValue(ATTR_NAME);
        String version = attrs.getValue(ATTR_VERSION);
        String requires = attrs.getValue(ATTR_REQUIRES);
        String entry = attrs.getValue(ATTR_ENTRY);
        String dependencies = attrs.getValue(ATTR_DEPENDENCIES);
        String dependenciesOptional = attrs.getValue(ATTR_DEPENDENCIES_OPTIONAL);
        String libRequires = attrs.getValue(ATTR_LIB_REQUIRES);
        String modRequires = attrs.getValue(ATTR_MOD_REQUIRES);
        String modOptional = attrs.getValue(ATTR_MOD_OPTIONAL);

        // Module-Name 是唯一的强制属性。
        if (name == null || name.isBlank()) {
            LogManager.serverWarn("ContentPack", "JAR '%s' missing required MANIFEST attribute (Module-Name) - skipping", jarName);
            return null;
        }
        name = name.trim();

        // Module-Entry 可缺省：没有入口类 = 纯数据包（L0 资源 / L1 JSON 内容），
        // 由加载器构造虚拟 ContentPack，使 assets/ 与 data/ 仍能挂载。
        // 规范：Module-Entry「有代码时必填」——无代码时不填是合法用法，不是错误。
        boolean dataOnly = (entry == null || entry.isBlank());
        entry = dataOnly ? null : entry.trim();

        if (version == null) {
            version = "0.0.0";
            LogManager.serverDebug("ContentPack", "JAR '%s' has no Module-Version, defaulting to 0.0.0", jarName);
        }

        LogManager.serverInfo("ContentPack", "Found ContentPack manifest: %s v%s (%s)",
                name, version,
                dataOnly ? "pure data pack, no entry class" : "entry: " + entry);
        if (dependencies != null && !dependencies.trim().isEmpty()) {
            LogManager.serverDebug("ContentPack", "  pack dependencies (hard): %s", dependencies);
        }
        if (dependenciesOptional != null && !dependenciesOptional.trim().isEmpty()) {
            LogManager.serverDebug("ContentPack", "  pack dependencies (optional): %s", dependenciesOptional);
        }
        if (libRequires != null && !libRequires.trim().isEmpty()) {
            LogManager.serverDebug("ContentPack", "  lib requires: %s", libRequires);
        }
        if (modRequires != null && !modRequires.trim().isEmpty()) {
            LogManager.serverDebug("ContentPack", "  mod requires: %s", modRequires);
        }
        if (modOptional != null && !modOptional.trim().isEmpty()) {
            LogManager.serverDebug("ContentPack", "  mod optional: %s", modOptional);
        }
        return new ContentPackManifest(name, version, requires, entry,
                dependencies, dependenciesOptional, libRequires, modRequires, modOptional);
    }

    /** 是否无入口类（纯数据包）。 */
    public boolean isDataOnly() {
        return moduleEntry == null;
    }

    /**
     * 解析 <b>硬依赖</b> 的包名列表（{@code Module-Dependencies}，逗号分隔）。
     *
     * <p>语义：缺失任何一个 → 本包被<b>跳过</b>（不加载）。若被依赖的包本身因兼容性失败
     * 被跳过，依赖它的包也会连带跳过（加载器做固定点传播）。
     *
     * <p>该属性<b>只影响顺序、存在性与可选性</b>，<b>不会</b>把依赖包的 jar 注入本包的
     * 类加载闭包 —— 跨包功能传递请用 {@link Capability} / {@link CapabilityRegistry}。
     *
     * <p>声明硬依赖同时带来顺序保证：被依赖包的 {@code register()} 一定先执行，
     * 因此本包在自己的 {@code register()} 里就能消费对方提供的能力。
     *
     * @return 依赖包名列表；未声明或空则返回空列表
     */
    public List<String> dependencyNames() {
        return parsePackDependencyList(moduleDependencies, ATTR_DEPENDENCIES, moduleName);
    }

    /**
     * 解析 <b>软依赖</b> 的包名列表（{@code Module-Dependencies-Optional}，逗号分隔）。
     *
     * <p>语义：缺失仅警告，本包照常加载。配合 {@link CapabilityRegistry}
     * 即可实现「包 B 存在时才启用这部分内容」：
     * <pre>{@code ctx.capability(SomeCaps.FOO).ifPresent(impl -> registerCompat(ctx, impl)); }</pre>
     *
     * <p><b>注意</b>：软依赖<b>不保证加载顺序</b>。若某段内容既需要 B 存在、又必须在 B
     * 注册完成后才能执行，请改用硬依赖 {@code Module-Dependencies}。
     *
     * @return 可选依赖包名列表；未声明或空则返回空列表
     */
    public List<String> optionalDependencyNames() {
        return parsePackDependencyList(moduleDependenciesOptional, ATTR_DEPENDENCIES_OPTIONAL, moduleName);
    }

    /** 逗号分隔的包名列表；容忍前后空白，并拒绝（不支持的）包间版本约束写法。 */
    private static List<String> parsePackDependencyList(String raw, String attrName, String selfName) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String s : raw.split(",")) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // 包间版本约束是被禁止的：契约版本轴只存在于 Module-Requires（对主 mod）。
            // 若有人写了 "gun-framework>=1.2"，取包名并明确警告，
            // 而不是把它当成一个「名字里带 >= 的包」而永远匹配不上。
            int op = indexOfVersionOperator(trimmed);
            if (op >= 0) {
                String onlyName = trimmed.substring(0, op).trim();
                LogManager.serverWarn("ContentPack",
                        "'%s' declared pack dependency '%s' with a version constraint in %s. "
                                + "Pack-to-pack version constraints are NOT supported - using '%s' as a plain dependency. "
                                + "Cross-pack contracts are versioned via Module-Requires (host mod contract).",
                        selfName, trimmed, attrName, onlyName);
                trimmed = onlyName;
            }
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 返回版本运算符在字符串中的起始下标，无则 -1。 */
    private static int indexOfVersionOperator(String s) {
        int best = -1;
        for (String op : new String[]{">=", "<=", "!=", "==", ">", "<", "=", "~", "^"}) {
            int i = s.indexOf(op);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }

    /**
     * 解析声明的外部 jar 库名列表（Module-Lib-Requires，逗号分隔）。
     * <p>这些库 jar 位于 contentpacks/_libs/<库名>/ 下，由加载器收集进类加载闭包——
     * 不被打包进本 contentpack，也不作为 mod 注册，仅提供类与资源。
     */
    public List<String> libNames() {
        if (moduleLibRequires == null || moduleLibRequires.trim().isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String s : moduleLibRequires.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    // ==================== 外部 mod 依赖（Module-Mod-Requires / Module-Mod-Optional）====================

    /**
     * 单个外部 mod 依赖声明
     *
     * @param modId   mod id（如 "sable"）
     * @param version 最低版本（可 null，表示无版本要求）
     */
    public record ModRequirement(String modId, String version) {}

    /**
     * 解析硬依赖的外部 mod 列表（Module-Mod-Requires，逗号分隔：modId 或 modId>=version）。
     */
    public List<ModRequirement> modRequirements() {
        return parseRequirements(moduleModRequires);
    }

    /**
     * 解析软依赖的外部 mod 列表（Module-Mod-Optional）。
     */
    public List<ModRequirement> optionalModRequirements() {
        return parseRequirements(moduleModOptional);
    }

    private static List<ModRequirement> parseRequirements(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        List<ModRequirement> result = new ArrayList<>();
        for (String s : raw.split(",")) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(">=");
            if (parts.length == 2) {
                result.add(new ModRequirement(parts[0].trim(), parts[1].trim()));
            } else {
                result.add(new ModRequirement(trimmed, null));
            }
        }
        return result;
    }

    /**
     * 验证硬依赖的外部 mod（Module-Mod-Requires）。
     *
     * @return 验证失败原因，null 表示全部满足
     */
    public String verifyModDependencies() {
        for (ModRequirement req : modRequirements()) {
            var container = net.neoforged.fml.ModList.get().getModContainerById(req.modId());
            if (container.isEmpty()) {
                return String.format("Requires mod '%s' which is not installed", req.modId());
            }
            if (req.version() != null) {
                String installed = container.get().getModInfo().getVersion().toString();
                if (compareVersions(installed, req.version()) < 0) {
                    return String.format("Requires mod '%s>=%s' but found %s", req.modId(), req.version(), installed);
                }
            }
        }
        return null;
    }

    /**
     * 检查缺失的可选依赖 mod（Module-Mod-Optional），仅用于警告日志。
     *
     * @return 缺失的可选 mod 描述列表（空 = 全部满足）
     */
    public List<String> missingOptionalMods() {
        List<String> missing = new ArrayList<>();
        for (ModRequirement req : optionalModRequirements()) {
            var container = net.neoforged.fml.ModList.get().getModContainerById(req.modId());
            if (container.isEmpty()) {
                missing.add(req.modId() + (req.version() != null ? ">=" + req.version() : ""));
            }
        }
        return missing;
    }

    /**
     * 验证依赖兼容性
     *
     * @param hostModVersion 主 mod 当前版本
     * @return 验证失败原因，null 表示通过
     */
    public String verifyCompatibility(String hostModVersion) {
        if (moduleRequires == null || moduleRequires.trim().isEmpty()) {
            return null;
        }

        try {
            String[] parts = moduleRequires.trim().split(">=");
            if (parts.length != 2) {
                return "Invalid dependency format: '" + moduleRequires + "'. Expected: modId>=version";
            }

            String requiredMod = parts[0].trim();
            String requiredVersion = parts[1].trim();

            if (!"catoxidesbattlerebuild".equalsIgnoreCase(requiredMod)) {
                return "Unsupported dependency mod: '" + requiredMod + "'";
            }

            if (compareVersions(hostModVersion, requiredVersion) < 0) {
                return String.format("Requires catoxidesbattlerebuild>=%s but found %s",
                        requiredVersion, hostModVersion);
            }
            return null;
        } catch (Exception e) {
            return "Failed to parse dependency: " + e.getMessage();
        }
    }

    /**
     * 扫描 JAR 中的资源文件（按前缀过滤）
     */
    public static List<String> listResources(JarFile jarFile, String prefix) {
        List<String> result = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(prefix)) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * 创建 JAR 的 ClassLoader（子优先，黑名单 parent-only）
     *
     * @param jarFile 单个插件 jar（自身空间，无依赖）
     * @return ChildFirstClassLoader
     */
    public static ChildFirstClassLoader createClassLoader(File jarFile) {
        return ChildFirstClassLoader.of(List.of(jarFile), ContentPackManifest.class.getClassLoader());
    }

    /**
     * 语义版本比较：-1=a<b, 0=a==b, 1=a>b
     */
    static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int vA = i < partsA.length ? parseIntVersionPart(partsA[i]) : 0;
            int vB = i < partsB.length ? parseIntVersionPart(partsB[i]) : 0;
            if (vA != vB) return vA < vB ? -1 : 1;
        }
        return 0;
    }

    private static int parseIntVersionPart(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
