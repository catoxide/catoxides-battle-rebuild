package example.contentpack.mapv2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 * 方块颜色解析：<b>JSON 覆盖 &gt; 群系 tint &gt; 原版 MapColor</b>。
 *
 * <p>{@code assets/map-pack/map_colors.json} 是可编辑的覆盖层（也是以后「纹理平均色烘焙」
 * 的 override 层）：烘焙结果作为默认表，JSON 只写想改的条目即可。
 *
 * <p>草/树叶/水这类方块标 {@code "tint": true}：优先用 {@code BlockColors} 的群系色，
 * 拿不到才回退到 JSON 里的颜色。
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
            if (tint >= 0) {
                return 0xFF000000 | (tint & 0xFFFFFF);
            }
            return 0xFF000000 | entry.rgb();
        }
        if (entry != null) {
            return 0xFF000000 | entry.rgb();
        }

        int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        if (tint >= 0) {
            return 0xFF000000 | (tint & 0xFFFFFF);
        }
        MapColor mapColor = state.getMapColor(level, pos);
        int rgb = mapColor == null ? 0x808080 : mapColor.col;
        return 0xFF000000 | (rgb & 0xFFFFFF);
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
