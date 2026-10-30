package example.contentpack.mapv2.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 群系 → 地图色。
 *
 * <p>优先查 {@code assets/map-pack/biome_colors.json}（每个群系一个标志性色系）；
 * 没收录的模组群系用 ID 的稳定哈希生成色相，保证「不同群系不同色」而不是全部同一个绿。
 */
public final class BiomePalette {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation FILE =
            ResourceLocation.fromNamespaceAndPath("map-pack", "biome_colors.json");

    private static final Map<String, Integer> COLORS = new HashMap<>();
    private static boolean loaded;

    private BiomePalette() {
    }

    /** 调色板里的颜色；没收录返回 0（调用方再决定回退）。 */
    static int direct(String biomeId) {
        if (!loaded) {
            load();
        }
        Integer color = COLORS.get(biomeId);
        return color == null ? 0 : color;
    }

    /** 总能给出一个颜色：调色板优先，否则用哈希色。 */
    static int color(String biomeId) {
        int direct = direct(biomeId);
        return direct != 0 ? direct : hashColor(biomeId);
    }

    public static void invalidate() {
        COLORS.clear();
        loaded = false;
    }

    /** 稳定色相 + 中等饱和/亮度，避免和真实地形抢眼。 */
    private static int hashColor(String biomeId) {
        int hash = biomeId == null ? 0 : biomeId.hashCode();
        float hue = (((hash % 360) + 360) % 360) / 360.0f;
        return hsvToRgb(hue, 0.42f, 0.72f);
    }

    private static int hsvToRgb(float hue, float saturation, float value) {
        int segment = (int) (hue * 6.0f) % 6;
        float fraction = hue * 6.0f - (int) (hue * 6.0f);
        int p = (int) (value * (1.0f - saturation) * 255.0f);
        int q = (int) (value * (1.0f - saturation * fraction) * 255.0f);
        int t = (int) (value * (1.0f - saturation * (1.0f - fraction)) * 255.0f);
        int v = (int) (value * 255.0f);
        return switch (segment) {
            case 0 -> 0xFF000000 | (v << 16) | (t << 8) | p;
            case 1 -> 0xFF000000 | (q << 16) | (v << 8) | p;
            case 2 -> 0xFF000000 | (p << 16) | (v << 8) | t;
            case 3 -> 0xFF000000 | (p << 16) | (q << 8) | v;
            case 4 -> 0xFF000000 | (t << 16) | (p << 8) | v;
            default -> 0xFF000000 | (v << 16) | (p << 8) | q;
        };
    }

    private static void load() {
        loaded = true;
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(FILE);
            if (resource.isEmpty()) {
                LOGGER.warn("[MapPack] {} missing; using hash colours for biomes", FILE);
                return;
            }
            try (InputStream in = resource.get().open();
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject colors = root.getAsJsonObject("colors");
                if (colors == null) {
                    return;
                }
                for (Map.Entry<String, JsonElement> entry : colors.entrySet()) {
                    try {
                        COLORS.put(entry.getKey(), 0xFF000000 | parseRgb(entry.getValue().getAsString()));
                    } catch (RuntimeException ex) {
                        LOGGER.warn("[MapPack] biome_colors: bad value for {}: {}",
                                entry.getKey(), ex.getMessage());
                    }
                }
                LOGGER.info("[MapPack] biome_colors loaded: {} entries", COLORS.size());
            }
        } catch (Exception ex) {
            LOGGER.warn("[MapPack] biome_colors load failed: {}", ex.getMessage());
        }
    }

    private static int parseRgb(String raw) {
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        if (hex.length() != 6) {
            throw new IllegalArgumentException("expected RRGGBB, got '" + raw + "'");
        }
        return Integer.parseInt(hex, 16) & 0xFFFFFF;
    }
}
