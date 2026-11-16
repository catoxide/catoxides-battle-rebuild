package example.contentpack.mapv2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.client.BedSurfaces;
import example.contentpack.mapv2.client.BlockSurfaces;
import example.contentpack.mapv2.client.ChestSurfaces;
import example.contentpack.mapv2.client.ConduitSurfaces;
import example.contentpack.mapv2.client.PotSurfaces;
import example.contentpack.mapv2.client.ShulkerSurfaces;
import example.contentpack.mapv2.client.SignSurfaces;
import example.contentpack.mapv2.client.SkullSurfaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ConduitBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 方块颜色解析，分两条口径：
 *
 * <ul>
 *   <li><b>面</b>（放大画贴图时记进表面栈）：JSON 覆盖 &gt; 模型 {@code tintindex} 的 tint
 *       &gt; 这一片贴图的平均色（{@link #textureDriven}）。{@link MapColor} 不参与 ——
 *       它只是低缩放的分类色，拿来当面的颜色会被渲染端当成 tint 乘到贴图上。</li>
 *   <li><b>平色</b>（缩略图 / 低缩放，{@link #colorFor}）：JSON 覆盖 &gt; 群系 tint &gt;
 *       采集到的这一列顶面均值 &gt; {@link MapColor}（没有记录时，如切片）&gt; 顶面贴图均值。</li>
 * </ul>
 *
 * <p>{@code assets/map-pack/map_colors.json} 是可编辑的覆盖层（也是以后「纹理平均色烘焙」
 * 的 override 层）：烘焙结果作为默认表，JSON 只写想改的条目即可。
 *
 * <p>草/树叶/水这类方块标 {@code "tint": true}：优先用 {@code BlockColors} 的群系色，
 * 拿不到才回退到 JSON 里的颜色。
 *
 * <p>没写 {@code mapColor(...)} 的方块（铁轨、中继器、玻璃板）默认是黑色
 * {@link MapColor#NONE}，这里改用顶面贴图的平均色（{@link BlockSurfaces#textureColor}）。
 */
public final class MapColors {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation FILE =
            ResourceLocation.fromNamespaceAndPath("map-pack", "map_colors.json");

    private static final Map<ResourceLocation, Entry> ENTRIES = new HashMap<>();
    private static boolean loaded;

    private MapColors() {
    }

    private record Entry(int rgb, boolean tint) {
    }

    /** 客户端主线程调用（渲染/采集线程）。失败只回退，不抛。 */
    public static int colorFor(BlockState state, ClientLevel level, BlockPos pos) {
        return colorFor(state, level, pos, -1);
    }

    /**
     * 采集端的平色：多一个 {@code recorded}（这一列终点的顶面均值，24 位 RGB，-1 = 没有）。
     *
     * <p>JSON 覆盖、群系 tint 和实体渲染方块的合成顶面照旧优先；轮到
     * {@link MapColor} 兜底时，如果采到了顶面就以记录色为准 —— 这和贴图层是同一条
     * 取色规则（{@link #textureDriven}）。{@code MapColor} 只是低缩放的分类色：
     * 盛开的杜鹃花（{@code MapColor.PLANT} = #007C00）顶面其实是橄榄绿 + 粉花，
     * 用分类色低缩放就是一整格纯绿。
     *
     * <p>切片（{@code put}）没有表面栈，照旧传 -1。
     */
    public static int colorFor(BlockState state, ClientLevel level, BlockPos pos, int recorded) {
        if (!loaded) {
            load();
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Entry entry = ENTRIES.get(id);

        if (entry != null && entry.tint()) {
            int tint = biomeTint(state, level, pos);
            if (tint < 0) {
                tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
            }
            if (tint != -1) {
                return 0xFF000000 | (tint & 0xFFFFFF);
            }
            return 0xFF000000 | entry.rgb();
        }
        if (entry != null) {
            return 0xFF000000 | entry.rgb();
        }
        // 床、箱子、告示牌的颜色来自各自渲染器的贴图，不是 MapColor：
        // 床的 MapColor 是「头白/脚染料色」，箱子/告示牌只有统一的木头色，
        // 表达不了盖顶、板顶和木材种类的差别
        if (state.getBlock() instanceof BedBlock) {
            int bed = BedSurfaces.flatColor(state);
            if (bed >= 0) {
                return 0xFF000000 | bed;
            }
        }
        if (state.getBlock() instanceof ChestBlock || state.getBlock() instanceof EnderChestBlock) {
            int chest = ChestSurfaces.flatColor(state);
            if (chest >= 0) {
                return 0xFF000000 | chest;
            }
        }
        if (state.getBlock() instanceof SignBlock) {
            int sign = SignSurfaces.flatColor(state);
            if (sign >= 0) {
                return 0xFF000000 | sign;
            }
        }
        // 潜影盒、潮涌核心、陶罐与生物头同理：MapColor 要么是统一的壳色（潜影盒是
        // 「紫」/核心是深海色），要么根本没有（头颅），真正的顶面只在渲染器贴图里
        if (state.getBlock() instanceof ShulkerBoxBlock) {
            int shulker = ShulkerSurfaces.flatColor(state);
            if (shulker >= 0) {
                return 0xFF000000 | shulker;
            }
        }
        if (state.getBlock() instanceof ConduitBlock) {
            int conduit = ConduitSurfaces.flatColor(level, pos);
            if (conduit >= 0) {
                return 0xFF000000 | conduit;
            }
        }
        if (state.getBlock() instanceof DecoratedPotBlock) {
            int pot = PotSurfaces.flatColor();
            if (pot >= 0) {
                return 0xFF000000 | pot;
            }
        }
        if (state.getBlock() instanceof AbstractSkullBlock) {
            int skull = SkullSurfaces.flatColor(state, level, pos);
            if (skull >= 0) {
                return 0xFF000000 | skull;
            }
        }
        if (state.is(Blocks.DAYLIGHT_DETECTOR)) {
            // 正/倒两个状态共用 MapColor.QUARTZ，只有顶面贴图分得开
            int texture = BlockSurfaces.textureColor(state);
            if (texture >= 0) {
                return 0xFF000000 | texture;
            }
        }
        if (state.is(Blocks.LAVA_CAULDRON)) {
            // 岩浆锅的 MapColor 是锅体的石头灰；从上看主要内容是岩浆
            return colorFor(Blocks.LAVA.defaultBlockState(), level, pos);
        }

        // -1 是「没有 tint」的约定；其余都按 RGB 取 —— 原版红石粉的 tint 是
        // Mth.color(...)（alpha 位为 0），负数则是 0xFF 开头的 ARGB，两种都合法
        int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        if (tint != -1) {
            return 0xFF000000 | (tint & 0xFFFFFF);
        }
        if (recorded >= 0) {
            // 走到这里已经确认没有 JSON 覆盖、0 号槽也没有 tint：顶面就是贴图原样，
            // 低缩放用采到的顶面均值，不回落到分类色
            return 0xFF000000 | (recorded & 0xFFFFFF);
        }
        MapColor mapColor = state.getMapColor(level, pos);
        if (mapColor == null || mapColor == MapColor.NONE) {
            // 铁轨、中继器、玻璃板这些注册时没写 mapColor，默认落到 NONE（黑色）。
            // 顶面贴图的平均色才是它们真正的颜色。
            int texture = BlockSurfaces.textureColor(state);
            if (texture >= 0) {
                return 0xFF000000 | texture;
            }
            return 0xFF808080;
        }
        return 0xFF000000 | (mapColor.col & 0xFFFFFF);
    }

    /**
     * 这一面在游戏里是否<b>就是贴图原样</b>：JSON 没覆盖，{@code BlockColors} 也没给这块
     * 上色。是的话记录色必须取这一片贴图的平均色（{@link BlockSurfaces#partColor}）。
     *
     * <p>{@link MapColor} 不能当这一面的颜色用：它只是低缩放用的分类色，和贴图差得远时
     * 就是错的 —— {@code MapColor.PLANT}（#007C00）的红蓝通道是 0，把它当 tint 乘上去，
     * 粉色花簇会只剩绿通道、盛开的杜鹃花会整格变纯绿；中继器的石板和红石火把、铁轨、
     * 玻璃板也各是各的颜色，整块取一个色会互相染色。
     * 渲染端的顶点色是「记录色 ÷ 贴图均值」，记录贴图均值才能还原成原色。
     *
     * <p>床、箱子、告示牌、潜影盒、潮涌核心、陶罐和阳光探测器也走贴图：它们的 MapColor
     * 都不随该表达的状态变化（床的头/脚、箱子的盖顶与木材种类、告示牌的木纹、染色潜影盒、
     * 激活后的笼子、探测器的正/倒），而实体渲染方块连外壳色都对不上。
     *
     * <p>生物头不在这里：它没有图集贴图（{@link BlockSurfaces.Part#flat} 平色层），
     * 走 {@link #colorFor} 的头顶平均色。
     */
    public static boolean textureDriven(BlockState state, ClientLevel level, BlockPos pos) {
        if (!loaded) {
            load();
        }
        boolean overridden = ENTRIES.containsKey(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        boolean tinted = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0) != -1;
        return spriteOwned(overridden, tinted);
    }

    /** 「有覆盖 / 有方块级 tint 时记录方块色，否则记录贴图均值」—— 纯规则，供离线自检。 */
    static boolean spriteOwned(boolean overridden, boolean tinted) {
        return !overridden && !tinted;
    }

    /**
     * 带 {@code tintindex} 的面用的颜色（原版口径）：JSON 覆盖 &gt; 群系 tint &gt;
     * {@code BlockColors} 的同一 tint 槽。返回 -1 表示没有 tint，调用方按贴图原色画。
     *
     * <p>不能沿用 {@link #colorFor}：它固定取 tint 槽 0，会把锅沿这类没 tint 的面
     * 也套上内容物的颜色。
     */
    public static int tintColor(BlockState state, ClientLevel level, BlockPos pos, int tintIndex) {
        if (!loaded) {
            load();
        }
        Entry entry = ENTRIES.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        if (entry != null && !entry.tint()) {
            return entry.rgb();
        }
        int tint = biomeTint(state, level, pos);
        if (tint == -1 && tintIndex >= 0) {
            tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, tintIndex);
        }
        return tint;
    }

    /**
     * 直接取「本格所在 quart」的群系色，绕开 {@code BlockColors}。
     *
     * <p>不能用 {@code BlockColors/ClientLevel.getBlockTint}：它按
     * {@code biomeBlendRadius} 平均 (2r+1)² 个采样点，每个采样点又走
     * {@code BiomeManager.getBiome} 的 8 角模糊查找，能落到相邻区块的 quart；
     * 邻块还没进 {@code ClientChunkCache} 时 {@code LevelReader.getNoiseBiome}
     * 回落到 {@code ClientLevel.getUncachedNoiseBiome}，那里固定返回平原
     * （1.21.1 源码 {@code ClientLevel.java:747}）。于是区块边缘 1~2 格的群系色
     * 被拉向平原水色 #3f76e4，并且随 5×5 窗口向里渗 3~4 格。
     * 这个值还会被 BlockTintCache 按区块缓存，采集时烘进瓦片就永久留下方格边。
     *
     * <p>{@code getNoiseBiomeAtPosition} 不做模糊、不跨区块：quart 一定落在
     * 当前区块（采集入口已保证它 FULL），查到的就是真实群系。
     *
     * @return 24 位 RGB；不在下表里的方块返回 -1，交回 {@code BlockColors}
     */
    private static int biomeTint(BlockState state, ClientLevel level, BlockPos pos) {
        Block block = state.getBlock();
        Biome biome = level.getBiomeManager().getNoiseBiomeAtPosition(pos).value();
        if (block == Blocks.WATER || block == Blocks.BUBBLE_COLUMN
                || block == Blocks.WATER_CAULDRON) {
            return biome.getWaterColor();
        }
        if (block == Blocks.GRASS_BLOCK || block == Blocks.SHORT_GRASS
                || block == Blocks.TALL_GRASS || block == Blocks.FERN
                || block == Blocks.LARGE_FERN || block == Blocks.POTTED_FERN
                || block == Blocks.SUGAR_CANE) {
            return biome.getGrassColor(pos.getX(), pos.getZ());
        }
        if (block == Blocks.OAK_LEAVES || block == Blocks.JUNGLE_LEAVES
                || block == Blocks.ACACIA_LEAVES || block == Blocks.DARK_OAK_LEAVES
                || block == Blocks.MANGROVE_LEAVES || block == Blocks.VINE) {
            return biome.getFoliageColor();
        }
        return -1;
    }

    /** 资源包重载/换世界时由调用方清空。 */
    public static void invalidate() {
        ENTRIES.clear();
        loaded = false;
    }

    private static void load() {
        loaded = true; // 失败也只尝试一次，避免每像素重试
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(FILE);
            if (resource.isEmpty()) {
                LOGGER.warn("[MapPack] {} missing; falling back to vanilla MapColor", FILE);
                return;
            }
            try (InputStream in = resource.get().open();
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject colors = root.getAsJsonObject("colors");
                if (colors == null) {
                    return;
                }
                int parsed = 0;
                for (Map.Entry<String, JsonElement> entry : colors.entrySet()) {
                    ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
                    if (id == null) {
                        LOGGER.warn("[MapPack] map_colors: bad block id '{}'", entry.getKey());
                        continue;
                    }
                    try {
                        Entry value = parseEntry(entry.getValue());
                        ENTRIES.put(id, value);
                        parsed++;
                    } catch (RuntimeException ex) {
                        LOGGER.warn("[MapPack] map_colors: bad value for '{}': {}",
                                entry.getKey(), ex.getMessage());
                    }
                }
                LOGGER.info("[MapPack] map_colors loaded: {} entries", parsed);
            }
        } catch (Exception ex) {
            LOGGER.warn("[MapPack] map_colors load failed: {}", ex.getMessage());
        }
    }

    private static Entry parseEntry(JsonElement element) {
        if (element.isJsonPrimitive()) {
            return new Entry(parseRgb(element.getAsString()), false);
        }
        JsonObject object = element.getAsJsonObject();
        int rgb = parseRgb(object.get("color").getAsString());
        boolean tint = object.has("tint") && object.get("tint").getAsBoolean();
        return new Entry(rgb, tint);
    }

    private static int parseRgb(String raw) {
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        if (hex.length() != 6) {
            throw new IllegalArgumentException("expected RRGGBB, got '" + raw + "'");
        }
        return Integer.parseInt(hex, 16) & 0xFFFFFF;
    }
}
