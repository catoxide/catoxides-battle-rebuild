package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.config.ConfigDecl;
import com.catoxide.catoxidesbattlerebuild.core.config.ConfigLangCoverage;
import com.catoxide.catoxidesbattlerebuild.core.config.ConfigSpecCompiler;
import com.catoxide.catoxidesbattlerebuild.core.config.PackConfigDeclarations;
import com.catoxide.catoxidesbattlerebuild.core.config.PackConfigRegistry;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;

/**
 * 配置声明 → ModConfigSpec → 主 mod 的 ModContainer 的桥。
 *
 * <p>时序：{@code ContentPackLoader.loadAll()} 之后、{@code executeAllRegistries()} 之前调用。
 * 这样包的 {@code register()} 里已经可以用 {@code context.config(id)} 拿到句柄，
 * 而配置本身仍挂在主 mod 上（内容包没有自己的 ModContainer）。
 *
 * <p>不装任何声明文件的包零影响：本类对它们不做任何事。
 */
public final class ContentPackConfigBridge {

    private static final String TAG = "PackConfig";

    private ContentPackConfigBridge() {
    }

    public static void registerAll(ContentPackContext context) {
        Path overrideDir = FMLPaths.CONFIGDIR.get()
                .resolve(CatoxidesBattleRebuildConstants.MODID)
                .resolve("pack-configs");

        int registered = 0;
        for (ContentPackRegistry.LoadedPack loaded : ContentPackRegistry.getAllPacks()) {
            if (ContentPackGraph.isQuarantined(loaded.id())) {
                LogManager.serverWarn(TAG, "Skipping config declarations of cancelled pack '%s'", loaded.id());
                continue;
            }

            List<ConfigDecl> decls = PackConfigDeclarations.load(
                    loaded.jarFile(), loaded.id(), overrideDir.resolve(loaded.id() + ".json"));

            for (ConfigDecl decl : decls) {
                String owner = loaded.id() + "/" + decl.id();
                ConfigLangCoverage.Result lang = ConfigLangCoverage.check(loaded.jarFile(), decl);
                if (!lang.complete()) {
                    if (!lang.langFound()) {
                        LogManager.serverError(TAG,
                                "Pack '%s': config '%s' rejected: no English display names found. "
                                        + "Add assets/<namespace>/lang/en_us.json at the JAR ROOT (not under resources/). "
                                        + "Missing %d key(s): %s",
                                owner, decl.id(), lang.missing().size(), lang.missingSummary());
                    } else {
                        LogManager.serverError(TAG,
                                "Pack '%s': config '%s' rejected: %d English display name(s) missing from "
                                        + "assets/<namespace>/lang/en_us.json: %s",
                                owner, decl.id(), lang.missing().size(), lang.missingSummary());
                    }
                    continue;
                }
                if (!PackConfigRegistry.claimFile(decl.file(), owner)) {
                    LogManager.serverError(TAG,
                            "Pack '%s': config file '%s' is already claimed by another config; declaration ignored",
                            owner, decl.file());
                    continue;
                }

                ConfigSpecCompiler.Compiled compiled;
                try {
                    compiled = ConfigSpecCompiler.compile(decl);
                } catch (RuntimeException e) {
                    LogManager.serverError(TAG, "Pack '%s': failed to compile config declaration: %s",
                            owner, e.getMessage());
                    continue;
                }

                context.registerConfig(decl.scope().toModConfigType(), compiled.spec(), decl.file());
                PackConfigRegistry.install(loaded.id(), decl.id(), decl.file(), compiled.values());
                LogManager.serverInfo(TAG, "Pack '%s': registered config '%s' (%s, %d option(s)) -> %s.toml",
                        loaded.id(), decl.id(), decl.scope(), compiled.values().size(), decl.file());
                registered++;
            }
        }

        if (registered > 0) {
            LogManager.serverInfo(TAG, "Registered %d declared config file(s); per-pack overrides: %s",
                    registered, overrideDir);
        }
    }
}
