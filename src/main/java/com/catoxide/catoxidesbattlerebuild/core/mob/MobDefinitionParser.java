package com.catoxide.catoxidesbattlerebuild.core.mob;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体定义 JSON 解析器
 * <p>把 entities/*.json 解析为 {@link MobDefinition}。格式：
 * <pre>
 * {
 *   "id": "modular_zombie_3",
 *   "namespace": "zombie3pack",          // 可选，缺省挂主 mod
 *   "displayName": "Modular Zombie 3",   // 可选
 *   "size": { "width": 0.6, "height": 1.95 },
 *   "attributes": { "maxHealth": 20.0, "attackDamage": 6.0, "movementSpeed": 0.25, "followRange": 16.0, "armor": 0.0 },
 *   "model": { "type": "entity", "id": "zombie3pack:modular_zombie_3" },
 *   "texture": "zombie3pack:textures/entity/modular_zombie_3.png",
 *   "sounds": { "ambient": "...", "hurt": "...", "death": "...", "step": "...", "swing": "..." },
 *   "animations": { "0": "still", "1": "walking", "3": "attack" },
 *   "behaviors": [
 *     { "type": "attack", "damage": 6.0, "cooldown": 20 },
 *     { "type": "ai", "goals": ["hunt_player", "random_stroll"] }
 *   ]
 * }
 * </pre>
 */
public final class MobDefinitionParser {

    private MobDefinitionParser() {}

    /** 从 JSON 字符串解析实体定义（字段缺省均有兜底，仅 id 必填） */
    public static MobDefinition parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String id = root.get("id").getAsString();
        String namespace = root.has("namespace") ? root.get("namespace").getAsString() : null;
        String displayName = root.has("displayName") ? root.get("displayName").getAsString() : id;

        float width = 0.6f;
        float height = 1.95f;
        if (root.has("size")) {
            JsonObject size = root.getAsJsonObject("size");
            width = size.has("width") ? size.get("width").getAsFloat() : width;
            height = size.has("height") ? size.get("height").getAsFloat() : height;
        }

        Map<String, Float> attributes = new HashMap<>();
        if (root.has("attributes")) {
            for (var entry : root.getAsJsonObject("attributes").entrySet()) {
                attributes.put(entry.getKey(), entry.getValue().getAsFloat());
            }
        }

        String modelType = "entity";
        ResourceLocation modelId = null;
        if (root.has("model")) {
            JsonObject model = root.getAsJsonObject("model");
            modelType = model.has("type") ? model.get("type").getAsString() : modelType;
            if (model.has("id")) {
                modelId = ResourceLocation.parse(model.get("id").getAsString());
            }
        }
        // 缺省模型 id：按 namespace（或主 mod）推断为 ns:id
        if (modelId == null) {
            modelId = ResourceLocation.fromNamespaceAndPath(
                    namespace != null ? namespace : "catoxidesbattlerebuild", id);
        }

        ResourceLocation texture = root.has("texture")
                ? ResourceLocation.parse(root.get("texture").getAsString())
                : null;

        MobSoundConfig sounds = MobSoundConfig.NONE;
        if (root.has("sounds")) {
            JsonObject s = root.getAsJsonObject("sounds");
            sounds = new MobSoundConfig(
                    parseLoc(s, "ambient"), parseLoc(s, "hurt"), parseLoc(s, "death"),
                    parseLoc(s, "step"), parseLoc(s, "swing"));
        }

        Map<Integer, String> stateAnimations = new HashMap<>();
        if (root.has("animations")) {
            for (var entry : root.getAsJsonObject("animations").entrySet()) {
                stateAnimations.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsString());
            }
        }

        List<BehaviorSpec> behaviors = new ArrayList<>();
        if (root.has("behaviors")) {
            for (var el : root.getAsJsonArray("behaviors")) {
                JsonObject b = el.getAsJsonObject();
                if (b.has("type")) {
                    behaviors.add(new BehaviorSpec(b.get("type").getAsString(), b));
                }
            }
        }

        return new MobDefinition(id, namespace, displayName, width, height, attributes,
                modelType, modelId, texture, sounds, stateAnimations, behaviors);
    }

    private static ResourceLocation parseLoc(JsonObject obj, String field) {
        return obj.has(field) && obj.get(field).isJsonPrimitive()
                ? ResourceLocation.parse(obj.get(field).getAsString())
                : null;
    }
}
