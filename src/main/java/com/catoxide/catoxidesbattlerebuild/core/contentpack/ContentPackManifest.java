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
 * Module-Dependencies: mission-core, gun-framework   (可选, 逗号分隔的依赖包名)
 * </pre>
 */
public record ContentPackManifest(
        String moduleName,
        String moduleVersion,
        String moduleRequires,
        String moduleEntry,
        String moduleDependencies
) {

    private static final String ATTR_NAME = "Module-Name";
    private static final String ATTR_VERSION = "Module-Version";
    private static final String ATTR_REQUIRES = "Module-Requires";
    private static final String ATTR_TYPE = "Module-Type";
    private static final String ATTR_ENTRY = "Module-Entry";
    private static final String ATTR_DEPENDENCIES = "Module-Dependencies";
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
            LogManager.serverDebug("ContentPack", "  dependencies: %s", dependencies);
        }
        return new ContentPackManifest(name, version, requires, entry, dependencies);
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
