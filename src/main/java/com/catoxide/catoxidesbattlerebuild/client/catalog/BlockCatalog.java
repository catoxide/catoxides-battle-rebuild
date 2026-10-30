package com.catoxide.catoxidesbattlerebuild.client.catalog;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackRegistry;
import com.catoxide.catoxidesbattlerebuild.core.registry.ContentHolders;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * 注册表快照（BlockCatalog）—— 方块/物品选取功能的「运行时真源」
 *
 * <h3>解决什么问题</h3>
 * 客户端可能同时存在多个 MC 实例（不同启动器 / 不同版本 / 不同 mod 集合），
 * 离线静态解析 mods/ 目录无法保证"扫到的是你想要的那个实例"。
 * 本类反过来：<b>让运行中的实例自己交快照</b>——每个实例在启动时（主菜单就绪、
 * 注册表已冻结、I18n 已加载）把完整的方块/物品注册表序列化到
 * <b>实例自己的配置目录</b>：{@code <instance>/config/catoxidesbattlerebuild/block-catalog.json}。
 *
 * <p>只写实例本地、不写任何系统级目录（%APPDATA% 等）：快照跟着实例走，
 * 实例删除则快照自然消失，零系统残留。
 *
 * <p>编辑器（独立 exe）的实例选取完全由用户点选驱动：用户指向实例根或
 * {@code mods/}（或 {@code versions/} 等），编辑器按相对路径推导实例根后
 * 直接读该实例的 config 快照 —— 选取器精确呈现该实例的内容，
 * 无需猜目录、与启动器布局无关。「最近实例」列表由编辑器自己的设置维护，
 * 与本 mod 无关（见 docs/block_catalog_format.md §6）。
 *
 * <h3>分组（group）</h3>
 * <ul>
 *   <li>{@code contentpack} —— 主 mod + 内容包注册的内容（{@link ContentHolders} 索引或主 mod 命名空间）；</li>
 *   <li>{@code vanilla} —— {@code minecraft} 命名空间；</li>
 *   <li>{@code mod} —— 其他 mod（{@code modId} 由 IModInfo 的命名空间归属解析）。</li>
 * </ul>
 *
 * <h3>触发时机</h3>
 * 首次 {@link ClientTickEvent.Post} 且主菜单已就绪（screen 非空）且 I18n 已加载
 * （{@code I18n.exists} 探测）时执行：无需进入世界（dev 环境 runClient 到主菜单即触发）。
 * 每个进程只写一次；未就绪的 tick 上廉价重试，60 秒后放弃（避免异常环境空转）。
 *
 * <h3>JSON 格式（formatVersion=1）</h3>
 * 见 {@code docs/block_catalog_format.md}。
 */
@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, value = Dist.CLIENT)
public final class BlockCatalog {

    public static final String TAG = "BlockCatalog";
    public static final int FORMAT_VERSION = 1;

    /** I18n 就绪探测 key（原版必有的核心 key） */
    private static final String I18N_READY_KEY = "gui.toTitle";

    /** 未就绪时最多重试的 tick 数（1200 tick = 60 秒） */
    private static final int MAX_RETRY_TICKS = 1200;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Map<String, Integer> GROUP_ORDER = Map.of("contentpack", 0, "vanilla", 1, "mod", 2);

    /** 每进程只写一次；失败重置以便下次 tick 重试 */
    private static final AtomicBoolean BUILT = new AtomicBoolean(false);

    /** 未就绪重试计数（构建开始后不再变化） */
    private static volatile int retryTicks;

    /** 最近一次成功构建的快照（供未来游戏内 UI 直接取用，免读文件） */
    public static volatile Catalog last;

    private BlockCatalog() {
    }

    // ==================== 数据记录（Gson 序列化）====================

    public record InstanceInfo(String directory, String key, String mcVersion, String loader, String loaderVersion) {
    }

    public record ModInfo(String id, String name, String version, String namespace) {
    }

    public record PackInfo(String id, String name, String version) {
    }

    /** 方块状态参数：type = boolean/int/string；values = 全部合法取值；default = defaultBlockState 取值 */
    public record PropInfo(String type, List<String> values,
                           @SerializedName("default") String defaultValue) {
    }

    public record BlockInfo(String id, String name, String group, String modId,
                            Map<String, PropInfo> properties) {
    }

    public record ItemInfo(String id, String name, String group, String modId, boolean blockItem) {
    }

    public record Catalog(int formatVersion, String generatedAt, InstanceInfo instance,
                          List<ModInfo> mods, List<PackInfo> contentPacks,
                          List<BlockInfo> blocks, List<ItemInfo> items) {
    }

    public static Optional<Catalog> get() {
        return Optional.ofNullable(last);
    }

    // ==================== 事件入口 ====================

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (BUILT.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            return; // 主菜单/界面尚未就绪（启动资源加载阶段）
        }
        if (!I18n.exists(I18N_READY_KEY)) {
            if (retryTicks >= MAX_RETRY_TICKS) {
                LogManager.clientWarn(TAG, "I18n not ready after " + MAX_RETRY_TICKS
                        + " ticks, registry catalog skipped (will not retry)");
                BUILT.set(true); // 标记为已处理，不再重试
                return;
            }
            retryTicks++;
            return;
        }
        try {
            Catalog catalog = build();
            Path local = writeJson(localTarget(), catalog);
            BUILT.set(true);
            LogManager.clientInfo(TAG, "Registry catalog written: blocks=%d items=%d contentPacks=%d -> %s",
                    catalog.blocks().size(), catalog.items().size(), catalog.contentPacks().size(), local);
        } catch (Throwable t) {
            // 保持 BUILT=false：下一次 tick 重试（60 秒窗口）；超限后放弃避免日志风暴
            if (retryTicks >= MAX_RETRY_TICKS) {
                BUILT.set(true);
            }
            retryTicks++;
            LogManager.clientError(TAG, "Failed to build/write registry catalog", t);
        }
    }

    // ==================== 构建 ====================

    static Catalog build() {
        Path gameDir = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        InstanceInfo instance = new InstanceInfo(
                gameDir.toString(),
                instanceKey(gameDir),
                modVersion("minecraft"),
                "neoforge",
                modVersion("neoforge"));

        // ---- mod 列表（身份指纹：编辑器据此判断实例匹配）+ 命名空间归属表 ----
        List<ModInfo> mods = new ArrayList<>();
        Map<String, String> nsToModId = new HashMap<>();
        for (IModInfo mi : ModList.get().getMods()) {
            mods.add(new ModInfo(mi.getModId(), mi.getDisplayName(),
                    String.valueOf(mi.getVersion()), mi.getNamespace()));
            if (mi.getNamespace() != null) {
                nsToModId.putIfAbsent(mi.getNamespace(), mi.getModId());
            }
        }
        mods.sort(Comparator.comparing(ModInfo::id));

        // ---- 内容包列表（拓扑序）----
        List<PackInfo> packs = new ArrayList<>();
        for (ContentPackRegistry.LoadedPack lp : ContentPackRegistry.getAllPacks()) {
            packs.add(new PackInfo(lp.id(), lp.displayName(), lp.version()));
        }

        // ---- 方块（完整状态空间：每个参数的全部取值 + 默认值）----
        List<BlockInfo> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) {
                continue;
            }
            String[] owner = ownerFor(id, ContentHolders::hasBlock, nsToModId);
            blocks.add(new BlockInfo(
                    id.toString(),
                    I18n.get(block.getDescriptionId()), // 游戏内真实 lang key（Util.makeDescriptionId 把 : 归一为 .）；缺失 key 时 I18n 返回 key 本身
                    owner[0],
                    owner[1],
                    propertiesOf(block.defaultBlockState())));
        }
        blocks.sort(Comparator
                .comparingInt((BlockInfo b) -> GROUP_ORDER.getOrDefault(b.group(), 99))
                .thenComparing(BlockInfo::id));

        // ---- 物品（含方块物品；blockItem=true 标记）----
        List<ItemInfo> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) {
                continue;
            }
            String[] owner = ownerFor(id, ContentHolders::hasItem, nsToModId);
            items.add(new ItemInfo(
                    id.toString(),
                    I18n.get(item.getDescriptionId()),
                    owner[0],
                    owner[1],
                    item instanceof BlockItem));
        }
        items.sort(Comparator
                .comparingInt((ItemInfo i) -> GROUP_ORDER.getOrDefault(i.group(), 99))
                .thenComparing(ItemInfo::id));

        Catalog catalog = new Catalog(FORMAT_VERSION,
                ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                instance, mods, packs, blocks, items);
        last = catalog;
        return catalog;
    }

    /**
     * 归属解析：[group, modId]
     * minecraft 命名空间 → vanilla；ContentHolders 索引或主 mod 命名空间 → contentpack
     * （modId 尽量解析到内容包 id，否则用命名空间）；其余 → mod（modId 由命名空间归属表解析）。
     */
    private static String[] ownerFor(ResourceLocation id, Predicate<ResourceLocation> ours,
                                     Map<String, String> nsToModId) {
        String ns = id.getNamespace();
        if ("minecraft".equals(ns)) {
            return new String[]{"vanilla", "minecraft"};
        }
        if (ours.test(id) || CatoxidesBattleRebuildConstants.MODID.equals(ns)) {
            for (ContentPackRegistry.LoadedPack lp : ContentPackRegistry.getAllPacks()) {
                if (lp.id().equals(ns)) {
                    return new String[]{"contentpack", lp.id()};
                }
            }
            return new String[]{"contentpack", ns};
        }
        return new String[]{"mod", nsToModId.getOrDefault(ns, ns)};
    }

    private static String modVersion(String modId) {
        return ModList.get().getMods().stream()
                .filter(mi -> modId.equals(mi.getModId()))
                .map(mi -> String.valueOf(mi.getVersion()))
                .findFirst()
                .orElse("?");
    }

    /** 默认状态的完整参数表（name → {type, values, default}） */
    private static Map<String, PropInfo> propertiesOf(BlockState state) {
        Map<String, PropInfo> props = new LinkedHashMap<>();
        for (Property<?> p : state.getProperties()) {
            props.put(p.getName(), propInfo(p, state));
        }
        return props;
    }

    /** 泛型方法统一通配符捕获：values/default 的取值名解析在单一类型域内完成 */
    private static <T extends Comparable<T>> PropInfo propInfo(Property<T> p, BlockState state) {
        List<String> values = new ArrayList<>();
        for (T v : p.getPossibleValues()) {
            values.add(p.getName(v));
        }
        T def = state.getValue(p);
        return new PropInfo(typeName(p.getValueClass()), values, p.getName(def));
    }

    private static String typeName(Class<?> cls) {
        return switch (cls.getSimpleName().toLowerCase(Locale.ROOT)) {
            case "integer" -> "int";
            default -> cls.getSimpleName().toLowerCase(Locale.ROOT);
        };
    }

    // ==================== 写出 ====================

    /**
     * 实例本地（唯一写出位置）：{@code <instance>/config/catoxidesbattlerebuild/block-catalog.json}。
     * 只写实例自己的配置目录，不产生任何系统级文件。
     */
    static Path localTarget() {
        return FMLPaths.CONFIGDIR.get().resolve(CatoxidesBattleRebuildConstants.MODID).resolve("block-catalog.json");
    }

    /** 实例标识：目录名 + 路径 MD5 前 8 位（同目录名的不同实例可区分） */
    static String instanceKey(Path gameDir) {
        String abs = gameDir.toString();
        String tail = gameDir.getFileName() != null ? gameDir.getFileName().toString() : "instance";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(abs.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            }
            return tail + "-" + sb;
        } catch (Exception e) {
            return tail + "-" + Integer.toHexString(abs.hashCode() & 0xffff);
        }
    }

    private static Path writeJson(Path target, Catalog catalog) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, GSON.toJson(catalog), StandardCharsets.UTF_8);
        return target;
    }
}
