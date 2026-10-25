package example.contentpack.map;

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
import net.minecraft.world.level.block.state.BlockState;
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
            int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
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
