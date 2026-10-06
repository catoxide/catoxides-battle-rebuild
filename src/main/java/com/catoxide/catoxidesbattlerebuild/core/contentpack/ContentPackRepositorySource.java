package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlags;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * ContentPack 资源挂载源
 * <p>把已注册的 contentpack jar 包装成 Minecraft 资源包（{@link Pack}），挂进资源树：
 * <ul>
 *   <li>{@code assets/<ns>/...} → {@link PackType#CLIENT_RESOURCES}（资源包：纹理/语言/声音等）</li>
 *   <li>{@code data/<ns>/...} → {@link PackType#SERVER_DATA}（数据包：配方/标签/战利品等）</li>
 * </ul>
 * jar 本身不需要 pack.mcmeta（不走 {@code readMetaAndCreate}，直接构造 Pack）。
 */
public final class ContentPackRepositorySource implements RepositorySource {

    private static final String TAG = "ContentPackResource";

    private final PackType packType;

    public ContentPackRepositorySource(PackType packType) {
        this.packType = packType;
    }

    @Override
    public void loadPacks(Consumer<Pack> onLoad) {
        for (ContentPackRegistry.LoadedPack loaded : ContentPackRegistry.getAllPacks()) {
            // 被隔离的包（自身出错，或硬依赖链上被连带取消）不挂载资源。
            // 这条是「一个包写坏 data/ 就把整个世界卡在安全模式」的解药：
            // 取消加载 = 它的 JSON 根本不进入注册表加载流程。
            if (ContentPackGraph.isQuarantined(loaded.id())) {
                LogManager.serverWarn(TAG, "Not mounting resources of cancelled ContentPack '%s' (%s)",
                        loaded.id(), ContentPackGraph.reasonOf(loaded.id()));
                continue;
            }
            File jar = loaded.jarFile();
            if (jar == null || !jar.isFile()) {
                continue;
            }
            try {
                onLoad.accept(buildPack(loaded, jar));
            } catch (Exception e) {
                LogManager.serverWarn(TAG, "Failed to build resource pack for '%s': %s",
                        loaded.id(), e.getMessage());
            }
        }
    }

    private Pack buildPack(ContentPackRegistry.LoadedPack loaded, File jar) {
        PackLocationInfo location = new PackLocationInfo(
                "contentpack/" + loaded.id(),
                Component.literal(loaded.displayName()),
                PackSource.BUILT_IN,
                Optional.of(new KnownPack("contentpack", loaded.id(), loaded.version()))
        );

        // 直接从 jar 文件提供资源（无需 pack.mcmeta）。
        //
        // 必须用 FilePackResources（zip/jar 语义）。原实现用的是 PathPackResources
        // （目录语义），传 jar 路径时 getNamespaces() 返回空集、getResource() 恒为 null
        // —— 内容包的 assets/ 与 data/ 实际上从未生效过。已用 PackMountProbe 实测确认：
        //   PathPackResources(jar) -> namespaces=[]，resource=null
        //   FilePackResources(jar) -> namespaces=[structure_example]，resource 可读
        Pack.ResourcesSupplier supplier = new FilePackResources.FileResourcesSupplier(jar);

        Pack.Metadata metadata = new Pack.Metadata(
                Component.literal(loaded.displayName()),
                PackCompatibility.COMPATIBLE,
                FeatureFlags.VANILLA_SET,
                List.of()
        );

        // required=true：内容包资源必须生效（不可由玩家手动关闭）
        return new Pack(
                location,
                supplier,
                metadata,
                new PackSelectionConfig(true, Pack.Position.TOP, false)
        );
    }
}
