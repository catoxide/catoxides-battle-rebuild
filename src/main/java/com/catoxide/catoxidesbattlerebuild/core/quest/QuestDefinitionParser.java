package com.catoxide.catoxidesbattlerebuild.core.quest;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务定义 JSON 解析器（基座数据驱动能力）。
 * <p>内容包把任务定义为 quests/*.json，解析为 {@link QuestDefinition} 后经
 * {@code ContentPackContext.registerQuest} 注册。
 * <p>组件（giver/goal/condition）由 {@code type} 字符串经 {@link QuestSystem} 的
 * 工厂注册表解析——工厂由内容包 registerGiverType/goalType/conditionType 注册，
 * 通用原语（proximity_giver / item_present / item_submit）由基座
 * {@link QuestBuiltinComponents} 注册。
 *
 * <p><b>解析是严格的</b>：缺 id、组件缺 type、type 未注册、工厂解析失败都会抛
 * {@link IllegalArgumentException}。静默丢弃未知组件会让任务「永远完不成」——
 * 加载阶段失败并指出是哪个文件/哪个组件，才是可诊断的。
 *
 * <p>JSON 格式：
 * <pre>{@code
 * {
 *   "id": "watch_tower",
 *   "title": "建造瞭望塔",
 *   "description": "在坐标 (100, 64, 100) 建造瞭望塔",
 *   "givers": [ { "type": "region", "center": [0,64,0], "radius": 10 } ],
 *   "goals": [ { "type": "build", "target": [100,64,100], "block": "minecraft:stone_bricks", "count": 64 } ],
 *   "conditions": [],
 *   "failConditions": [],
 *   "children": [],
 *   "location": [100, 64, 100],
 *   "priority": 1.0,
 *   "repeatable": false,
 *   "cooldownTicks": 0
 * }
 * }</pre>
 *
 * <p>{@code repeatable}/{@code cooldownTicks} 决定自动发放能否再来一次，
 * 语义见 {@link QuestGivePolicy}（一次性 = 收到过就不发；可重复 = 终态 + cooldown 后再发，每次新实例 id）。
 */
public final class QuestDefinitionParser {

    private static final String TAG = "QuestParser";

    private QuestDefinitionParser() {
    }

    /**
     * 解析任务定义 JSON。
     *
     * @param json      任务定义 JSON 文本
     * @param namespace 默认命名空间（无显式 namespace 的 id 前缀；通常为内容包 modId）
     * @return 解析后的定义（含子任务树递归解析）
     * @throws IllegalArgumentException 结构非法 / 组件类型未注册 / 工厂解析失败
     */
    public static QuestDefinition parse(String json, String namespace) {
        JsonObject root;
        try {
            JsonElement rootEl = JsonParser.parseString(json);
            if (!rootEl.isJsonObject()) {
                throw new IllegalArgumentException("root must be a JSON object");
            }
            root = rootEl.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid quest JSON: " + e.getMessage(), e);
        }
        if (!root.has("id") || root.get("id").getAsString().isBlank()) {
            throw new IllegalArgumentException("missing required field 'id'");
        }

        String rawId = root.get("id").getAsString().trim();
        ResourceLocation id = rawId.contains(":")
                ? ResourceLocation.tryParse(rawId)
                : ResourceLocation.fromNamespaceAndPath(namespace, rawId);
        if (id == null) {
            throw new IllegalArgumentException("invalid quest id '" + rawId + "'");
        }
        Component title = text(root, "title", "未命名任务");
        Component description = text(root, "description", "");

        List<IQuestGiver> givers = parseGivers(root.getAsJsonArray("givers"), id);
        List<IQuestGoal> goals = parseGoals(root.getAsJsonArray("goals"), id);
        List<IQuestCondition> conditions = parseConditions(root.getAsJsonArray("conditions"), id, "condition");
        List<IQuestCondition> failConditions = parseConditions(root.getAsJsonArray("failConditions"), id, "failCondition");
        List<QuestDefinition> children = parseChildren(root.getAsJsonArray("children"), namespace);
        Vec3 location = parseLocation(root.get("location"));
        float priority = root.has("priority") ? root.get("priority").getAsFloat() : 0f;
        boolean repeatable = boolField(root, "repeatable", false);
        int cooldownTicks = nonNegativeIntField(root, "cooldownTicks", 0);

        LogManager.serverInfo(TAG, "Parsed quest '{}': goals={}, givers={}, children={}, repeatable={}, cooldownTicks={}",
                id, goals.size(), givers.size(), children.size(), repeatable, cooldownTicks);
        return new QuestDefinition(id, title, description,
                givers, goals, conditions, failConditions, children, location, priority,
                repeatable, cooldownTicks);
    }

    // ========== 组件解析（经 QuestSystem 工厂注册表） ==========

    private static List<IQuestGiver> parseGivers(JsonArray arr, ResourceLocation questId) {
        List<IQuestGiver> result = new ArrayList<>();
        if (arr == null) {
            return result;
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = objectAt(arr, i, questId, "giver");
            String type = typeOf(obj, questId, "giver", i);
            var factory = QuestSystem.getInstance().getGiverFactory(type);
            if (factory.isEmpty()) {
                throw new IllegalArgumentException(componentError(questId, "giver", i, type, "type not registered"));
            }
            result.add(applyFactory(factory.get(), obj, questId, "giver", i, type));
        }
        return result;
    }

    private static List<IQuestGoal> parseGoals(JsonArray arr, ResourceLocation questId) {
        List<IQuestGoal> result = new ArrayList<>();
        if (arr == null) {
            return result;
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = objectAt(arr, i, questId, "goal");
            String type = typeOf(obj, questId, "goal", i);
            var factory = QuestSystem.getInstance().getGoalFactory(type);
            if (factory.isEmpty()) {
                throw new IllegalArgumentException(componentError(questId, "goal", i, type, "type not registered"));
            }
            result.add(applyFactory(factory.get(), obj, questId, "goal", i, type));
        }
        return result;
    }

    private static List<IQuestCondition> parseConditions(JsonArray arr, ResourceLocation questId, String kind) {
        List<IQuestCondition> result = new ArrayList<>();
        if (arr == null) {
            return result;
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = objectAt(arr, i, questId, kind);
            String type = typeOf(obj, questId, kind, i);
            var factory = QuestSystem.getInstance().getConditionFactory(type);
            if (factory.isEmpty()) {
                throw new IllegalArgumentException(componentError(questId, kind, i, type, "type not registered"));
            }
            result.add(applyFactory(factory.get(), obj, questId, kind, i, type));
        }
        return result;
    }

    private static <T> T applyFactory(java.util.function.Function<JsonObject, T> factory, JsonObject obj,
                                      ResourceLocation questId, String kind, int index, String type) {
        try {
            T component = factory.apply(obj);
            if (component == null) {
                throw new IllegalArgumentException("factory returned null");
            }
            return component;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    componentError(questId, kind, index, type, "invalid: " + e.getMessage()), e);
        }
    }

    private static JsonObject objectAt(JsonArray arr, int index, ResourceLocation questId, String kind) {
        JsonElement el = arr.get(index);
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException(
                    "quest '" + questId + "': " + kind + " #" + index + " must be a JSON object");
        }
        return el.getAsJsonObject();
    }

    private static String typeOf(JsonObject obj, ResourceLocation questId, String kind, int index) {
        if (!obj.has("type") || obj.get("type").getAsString().isBlank()) {
            throw new IllegalArgumentException(
                    "quest '" + questId + "': " + kind + " #" + index + " missing required field 'type'");
        }
        return obj.get("type").getAsString();
    }

    private static String componentError(ResourceLocation questId, String kind, int index, String type, String detail) {
        return "quest '" + questId + "': " + kind + " #" + index + " (" + type + ") " + detail;
    }

    private static List<QuestDefinition> parseChildren(JsonArray arr, String namespace) {
        List<QuestDefinition> result = new ArrayList<>();
        if (arr == null) {
            return result;
        }
        for (JsonElement el : arr) {
            result.add(parse(el.toString(), namespace));
        }
        return result;
    }

    // ========== 工具 ==========

    private static Component text(JsonObject obj, String key, String def) {
        return Component.literal(obj.has(key) ? obj.get(key).getAsString() : def);
    }

    /** 严格布尔：写了就必须是 JSON boolean（"yes" 这类会被 getAsBoolean 静默当成 false） */
    private static boolean boolField(JsonObject obj, String key, boolean fallback) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("'" + key + "' must be a boolean, got: " + el);
        }
        return el.getAsBoolean();
    }

    /** 严格非负整数（1.5 不会被静默截断成 1） */
    private static int nonNegativeIntField(JsonObject obj, String key, int fallback) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("'" + key + "' must be a number, got: " + el);
        }
        double d = el.getAsDouble();
        if (d != Math.floor(d) || d < 0 || d > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("'" + key + "' must be a non-negative integer, got: " + el);
        }
        return (int) d;
    }

    private static Vec3 parseLocation(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return null;
        }
        JsonArray arr = el.getAsJsonArray();
        return new Vec3(arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble());
    }
}
