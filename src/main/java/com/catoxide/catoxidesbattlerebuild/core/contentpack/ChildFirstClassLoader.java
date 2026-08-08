package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * 子优先类加载器（Child-First ClassLoader）
 * <p>ContentPack 插件代码的类加载器，解决类身份隔离问题：
 * <ul>
 *   <li><b>黑名单（parent-only）</b>：JVM 核心、Minecraft/NeoForge、三方基础设施库、
 *       以及主 mod API 包——这些<b>永远只从主环境加载</b>（绝不回落到 jar 内副本），
 *       保证接口/基类单例一致（防止 ClassCastException 与双份类幽灵 bug）。</li>
 *   <li><b>子优先（child-first）</b>：其余类先在本 loader 的 jar 空间（自身 + 显式声明的依赖插件）中查找，
 *       找不到再委托 parent——保证插件代码真正从 jar 加载，类身份由 jar 决定。</li>
 *   <li><b>委托链</b>：构造时传入「自身 + 所有可达依赖插件的 jar」合并空间，
 *       插件 A 可加载其声明的依赖插件 B 的类，而 B 无法看到 A（单向、显式依赖）。</li>
 * </ul>
 *
 * <p><b>黑名单的防御边界</b>：黑名单防止<b>无意的类冲突</b>（打包时混入基础设施类导致的幽灵 bug）
 * 与<b>类伪装</b>（jar 里创建黑名单包名的类永远不会被加载）。但它<b>不是安全边界</b>——
 * 任何能执行代码的 jar 都能通过反射 / Unsafe 访问任意类（MC mod 生态共性：装 mod 即信任）。
 *
 * <p><b>依赖委托链</b>：插件间通过 MANIFEST 的 {@code Module-Dependencies} 属性显式声明依赖
 * （逗号分隔的包名列表）。加载器按「可见闭包」（自身 + 所有可达依赖）构建每个插件的加载空间。
 */
public final class ChildFirstClassLoader extends URLClassLoader {

    /** 必须 parent-only 的前缀（基础设施 + 主 mod API） */
    private static final String[] PARENT_FIRST_PREFIXES = {
            // JVM 核心
            "java.", "javax.", "jdk.", "sun.",
            // Minecraft / NeoForge / Mojang
            "net.minecraft.",
            "net.neoforged.",
            "com.mojang.",
            // 三方基础设施（库 mod / 常用库，由主环境提供单例）
            "cn.solarmoon.spark_core.",
            "software.bernie.geckolib.",
            "thedarkcolour.",
            "com.google.gson.",
            "com.google.common.",
            "org.slf4j.",
            "io.netty.",
            "it.unimi.dsi.",
            "org.joml.",
            "org.apache.",
            "kotlin.",
            "org.jetbrains.",
            // 主 mod API（插件实现的接口/基类必须与主环境一致）
            "com.catoxide.catoxidesbattlerebuild."
    };

    public ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /**
     * 便捷构造：从 jar 列表创建（自身 + 显式声明的依赖插件的 jar，自身优先）。
     */
    public static ChildFirstClassLoader of(List<File> jars, ClassLoader parent) {
        List<URL> urls = new ArrayList<>();
        for (File jar : jars) {
            try {
                urls.add(jar.toURI().toURL());
            } catch (Exception e) {
                // 忽略无效 jar
            }
        }
        return new ChildFirstClassLoader(urls.toArray(new URL[0]), parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (isParentFirst(name)) {
                    // 黑名单：只走 parent，绝不回落 jar 内副本
                    c = getParent().loadClass(name);
                } else {
                    try {
                        // 子优先：先在自己（+依赖）空间找
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        // 找不到再委托 parent
                        c = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    private boolean isParentFirst(String name) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
