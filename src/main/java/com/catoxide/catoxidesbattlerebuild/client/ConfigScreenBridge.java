package com.catoxide.catoxidesbattlerebuild.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 把主 mod 的配置页接到 NeoForge 内置配置屏。
 *
 * <p>所有内容包的配置都注册在主 mod 的 ModContainer 上，因此内置屏会按
 * 「配置类型 → 文件 → 分区 → 选项」把它们集中列出来——这就是「收集页」，
 * 不另写界面。本类必须只在客户端加载（引用 {@code ConfigurationScreen}）。
 */
public final class ConfigScreenBridge {

    private ConfigScreenBridge() {
    }

    public static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) ConfigurationScreen::new);
    }
}
