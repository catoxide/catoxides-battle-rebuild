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
 * Module-Name: custom-zombies
 * Module-Version: 1.0.0
 * Module-Requires: catoxidesbattlerebuild>=1.0.0
 * Module-Type: contentpack
 * Module-Entry: com.example.mypack.CustomZombiesPack
 * Module-Dependencies: mission-core, gun-framework   (可选, contentpack 间依赖)
 * Module-Mod-Requires: sable>=2.0.3                  (可选, 硬依赖的外部 mod, 缺失则跳过本包)
 * Module-Mod-Optional: lso>=2.4.5                    (可选, 软依赖的外部 mod, 缺失仅警告)
 * </pre>
 */
public record ContentPackManifest(
        String moduleName,
        String moduleVersion,
        String moduleRequires,
        String moduleEntry,
        String moduleDependencies,
        String moduleModRequires,
        String moduleModOptional
) {

    private static final String ATTR_NAME = "Module-Name";
    private static final String ATTR_VERSION = "Module-Version";
    private static final String ATTR_REQUIRES = "Module-Requires";
    private static final String ATTR_TYPE = "Module-Type";
    private static final String ATTR_ENTRY = "Module-Entry";
    private static final String ATTR_DEPENDENCIES = "Module-Dependencies";
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
        String modRequires = attrs.getValue(ATTR_MOD_REQUIRES);
        String modOptional = attrs.getValue(ATTR_MOD_OPTIONAL);

        if (name == null || entry == null) {
            LogManager.serverWarn("ContentPack", "JAR '%s' missing required MANIFEST attributes (Module-Name, Module-Entry)", jarName);
            return null;
        }

        if (version == null) {
            version = "0.0.0";
            LogManager.serverDebug("ContentPack", "JAR '%s' has no Module-Version, defaulting to 0.0.0", jarName);
        }

        LogManager.serverInfo("ContentPack", "Found ContentPack manifest: %s v%s (entry: %s)",
                name, version, entry);
        if (dependencies != null && !dependencies.trim().isEmpty()) {
            LogManager.serverDebug("ContentPack", "  pack dependencies: %s", dependencies);
        }
        if (modRequires != null && !modRequires.trim().isEmpty()) {
            LogManager.serverDebug("ContentPack", "  mod requires: %s", modRequires);
        }
        if (modOptional != null && !modOptional.trim().isEmpty()) {
            LogManager.serverDebug("ContentPack", "  mod optional: %s", modOptional);
        }
        return new ContentPackManifest(name, version, requires, entry, dependencies, modRequires, modOptional);
    }

    /**
     * 解析显式声明的依赖包名列表（Module-Dependencies，逗号分隔）。
     *
     * @return 依赖包名列表；未声明或空则返回空列表
     */
    public List<String> dependencyNames() {
        if (moduleDependencies == null || moduleDependencies.trim().isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String s : moduleDependencies.split(",")) {
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
