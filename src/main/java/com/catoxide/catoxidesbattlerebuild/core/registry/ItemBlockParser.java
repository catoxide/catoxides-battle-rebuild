package com.catoxide.catoxidesbattlerebuild.core.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品/方块定义 JSON 解析器
 * <p>
 * 与 {@code MobDefinitionParser} 同风格：字段缺省有兜底，只有 {@code id} 必填。
 * 解析阶段只做「形状校验」（能读出来）；语义校验（参数合法性等）由
 * {@link BatchContentRegistrar} 在注册阶段统一做并归并进问题清单。
 */
public final class ItemBlockParser {

    private ItemBlockParser() {
    }

    // ==================== 物品 ====================

    public static ItemDefinition parseItem(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String id = root.get("id").getAsString();
        String namespace = strOrNull(root, "namespace");
        Map<String, String> lang = parseLang(root.get("lang"));
        int stackSize = isNumber(root.get("stackSize"))
                ? root.get("stackSize").getAsInt() : ItemDefinition.DEFAULT_STACK_SIZE;
        String rarity = root.has("rarity") ? root.get("rarity").getAsString() : "common";
        boolean creative = !root.has("creative") || root.get("creative").getAsBoolean();
        boolean fireResistant = root.has("fireResistant") && root.get("fireResistant").getAsBoolean();

        return new ItemDefinition(id, namespace, lang, stackSize, rarity, creative, fireResistant);
    }

    // ==================== 方块 ====================

    public static BlockDefinition parseBlock(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String id = root.get("id").getAsString();
        String namespace = strOrNull(root, "namespace");
        Map<String, String> lang = parseLang(root.get("lang"));
        BlockDefinition.BlockMaterialSpec material = parseMaterial(root.get("material"));
        List<BlockPropertySpec> properties = parseProperties(root.get("properties"));
        BlockDefinition.BlockEntitySpec blockEntity = parseBlockEntity(root.get("blockEntity"));
        BlockDefinition.RedstoneSpec redstone = parseRedstone(root.get("redstone"));
        BlockDefinition.LootSpec loot = parseLoot(root.get("loot"));
        List<String> tags = parseStringList(root.get("tags"));
        boolean creative = !root.has("creative") || root.get("creative").getAsBoolean();
        boolean blockItem = !root.has("blockItem") || root.get("blockItem").getAsBoolean();

        return new BlockDefinition(id, namespace, lang, material, properties, blockEntity, redstone, loot, tags, creative, blockItem);
    }

    // ==================== 分段解析 ====================

    private static BlockDefinition.BlockMaterialSpec parseMaterial(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        float hardness = num(o, "hardness", 1.5f);
        float resistance = num(o, "resistance", 6.0f);
        String sound = strOrDefault(o, "sound", "minecraft:block.stone");
        int lightLevel = o.has("lightLevel") ? o.get("lightLevel").getAsInt() : 0;
        float friction = num(o, "friction", 0.6f);
        boolean transparent = bool(o, "transparent", false);
        boolean randomTicks = bool(o, "randomTicks", false);
        boolean requiresTool = bool(o, "requiresTool", false);
        boolean solid = !o.has("solid") || o.get("solid").getAsBoolean();
        return new BlockDefinition.BlockMaterialSpec(
                hardness, resistance, sound, lightLevel, friction, transparent, randomTicks, requiresTool, solid);
    }

    private static List<BlockPropertySpec> parseProperties(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return List.of();
        }
        List<BlockPropertySpec> out = new ArrayList<>();
        for (JsonElement e : el.getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            String name = strOrDefault(o, "name", null);
            String type = strOrDefault(o, "type", null);
            if (name == null || type == null) {
                continue; // 形状校验在注册阶段报（保留整条以便定位）
            }
            List<String> values = parseStringList(o.get("values"));
            int min = o.has("min") ? o.get("min").getAsInt() : 0;
            int max = o.has("max") ? o.get("max").getAsInt() : 15;
            String def = strOrNull(o, "default");
            String labelZh = strOrNull(o, "labelZh");
            String description = strOrNull(o, "description");
            out.add(new BlockPropertySpec(name, type, values, min, max, def, labelZh, description));
        }
        return List.copyOf(out);
    }

    private static BlockDefinition.BlockEntitySpec parseBlockEntity(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String type = strOrDefault(o, "type", BlockDefinition.BlockEntitySpec.TYPE_GENERIC);
        String className = strOrNull(o, "class");
        return new BlockDefinition.BlockEntitySpec(type, className);
    }

    /** 红石灯行为（可选）。对象存在但缺 property 时也返回对象（property=null），注册阶段报问题。 */
    private static BlockDefinition.RedstoneSpec parseRedstone(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String property = strOrNull(o, "property");
        int lightLit = isNumber(o.get("lightLit"))
                ? o.get("lightLit").getAsInt() : BlockDefinition.RedstoneSpec.DEFAULT_LIGHT_LIT;
        int lightUnlit = isNumber(o.get("lightUnlit"))
                ? o.get("lightUnlit").getAsInt() : BlockDefinition.RedstoneSpec.DEFAULT_LIGHT_UNLIT;
        return new BlockDefinition.RedstoneSpec(property, lightLit, lightUnlit);
    }

    private static BlockDefinition.LootSpec parseLoot(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            boolean selfDrop = !o.has("selfDrop") || o.get("selfDrop").getAsBoolean();
            return new BlockDefinition.LootSpec(selfDrop);
        }
        return BlockDefinition.LootSpec.defaultSelfDrop();
    }

    // ==================== 通用 ====================

    private static Map<String, String> parseLang(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return out;
    }

    private static List<String> parseStringList(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement e : el.getAsJsonArray()) {
            if (e.isJsonPrimitive()) {
                out.add(e.getAsString());
            }
        }
        return List.copyOf(out);
    }

    private static String strOrNull(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String strOrDefault(JsonObject o, String key, String fallback) {
        String v = strOrNull(o, key);
        return v != null ? v : fallback;
    }

    private static float num(JsonObject o, String key, float fallback) {
        return isNumber(o.get(key)) ? o.get(key).getAsFloat() : fallback;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return isBoolean(o.get(key)) ? o.get(key).getAsBoolean() : fallback;
    }

    /** 本构建的 gson 补丁版把 isNumber/isBoolean 收进了 JsonPrimitive */
    private static boolean isNumber(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isNumber();
    }

    private static boolean isBoolean(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isBoolean();
    }
}
