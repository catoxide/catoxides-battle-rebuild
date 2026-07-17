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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private ContentPackLoader() {}

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

        // Step 1: 扫描所有目录
        for (String dirPath : scanDirs) {
            scanDirectory(dirPath, hostVersion, discovered);
        }

        if (discovered.isEmpty()) {
            LogManager.serverInfo(TAG, "No ContentPack JARs found in scanned directories");
            LogManager.serverInfo(TAG, "Expected directories: %s", scanDirs);
            LogManager.serverInfo(TAG, "To add ContentPacks, place .jar files with Module-Type=contentpack in these directories");
            return 0;
        }

        LogManager.serverInfo(TAG, "Discovered %d ContentPack candidate(s)", discovered.size());

        // Step 2: 加载并注册
        int loaded = 0;
        int failed = 0;
        int skipped = 0;

        for (ScannablePack pack : discovered) {
            if (pack.skipReason != null) {
                skipped++;
                continue;
            }

            if (loadAndRegister(pack, hostVersion)) {
                loaded++;
            } else {
                failed++;
            }
        }

        // 总结
        LogManager.serverInfo(TAG, "=== ContentPack Load Summary ===");
        LogManager.serverInfo(TAG, "Loaded: %d, Failed: %d, Skipped: %d", loaded, failed, skipped);
        LogManager.serverInfo(TAG, "Total in registry: %d", ContentPackRegistry.getPackCount());

        return loaded;
    }

    private static void scanDirectory(String dirPath, String hostVersion, List<ScannablePack> discovered) {
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            LogManager.serverDebug(TAG, "ContentPack directory '%s' does not exist, skipping", dirPath);
            return;
        }

        File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            LogManager.serverDebug(TAG, "No .jar files found in '%s'", dirPath);
            return;
        }

        LogManager.serverInfo(TAG, "Scanning %d .jar file(s) in %s", jars.length, dirPath);

        for (File jar : jars) {
            ContentPackManifest manifest = ContentPackManifest.fromJar(jar);
            if (manifest == null) {
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

            // 兼容性验证
            String compatIssue = manifest.verifyCompatibility(hostVersion);
            if (compatIssue != null) {
                LogManager.serverWarn(TAG, "ContentPack '%s' compatibility issue: %s",
                        manifest.moduleName(), compatIssue);
                discovered.add(new ScannablePack(jar, manifest, compatIssue));
                continue;
            }

            LogManager.serverInfo(TAG, "  ✓ '%s' v%s passes compatibility check",
                    manifest.moduleName(), manifest.moduleVersion());
            discovered.add(new ScannablePack(jar, manifest, null));
        }
    }

    /**
     * 加载并注册单个 ContentPack
     */
    private static boolean loadAndRegister(ScannablePack pack, String hostVersion) {
        File jar = pack.jarFile;
        ContentPackManifest manifest = pack.manifest;

        LogManager.serverInfo(TAG, "Loading ContentPack: '%s' v%s", manifest.moduleName(), manifest.moduleVersion());

        // Step 1: 创建 ClassLoader
        URLClassLoader classLoader;
        try {
            classLoader = ContentPackManifest.createClassLoader(jar);
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

        LogManager.serverInfo(TAG, "  ✓ Successfully loaded ContentPack '%s'", contentPack.getId());
        return true;
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
        for (SparkPackage pack : pendingSparkPackages) {
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
     * 在主 mod 构造完成后调用，执行所有 ContentPack 的 register() 和 init()
     */
    public static void executeAllRegistries(ContentPackContext context) {
        LogManager.serverInfo(TAG, "Executing registries for %d ContentPack(s)", ContentPackRegistry.getPackCount());

        for (ContentPackRegistry.LoadedPack loaded : ContentPackRegistry.getAllPacks()) {
            try {
                LogManager.serverInfo(TAG, "  Registering: '%s'", loaded.id());
                loaded.pack().register(context);
            } catch (Exception e) {
                LogManager.serverError(TAG, String.format("Failed to register ContentPack '%s': %s",
                        loaded.id(), e.getMessage()), e);
            }
        }

        ContentPackRegistry.lock();

        for (ContentPackRegistry.LoadedPack loaded : ContentPackRegistry.getAllPacks()) {
            try {
                loaded.pack().init();
            } catch (Exception e) {
                LogManager.serverError(TAG, String.format("Failed to init ContentPack '%s': %s",
                        loaded.id(), e.getMessage()), e);
            }
        }

        LogManager.serverInfo(TAG, "All ContentPack registries executed");
    }

    /**
     * 扫描结果包装
     */
    private record ScannablePack(
            File jarFile,
            ContentPackManifest manifest,
            String skipReason
    ) {}
}
