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
 * 工厂注册表解析——工厂由内容包 registerGiverType/goalType/conditionType 注册。
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
 *   "priority": 1.0
 * }
 * }</pre>
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
     */
    public static QuestDefinition parse(String json, String namespace) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String rawId = root.get("id").getAsString();
        ResourceLocation id = rawId.contains(":")
                ? ResourceLocation.parse(rawId)
                : ResourceLocation.fromNamespaceAndPath(namespace, rawId);
        Component title = text(root, "title", "未命名任务");
        Component description = text(root, "description", "");

        List<IQuestGiver> givers = parseGivers(root.getAsJsonArray("givers"));
        List<IQuestGoal> goals = parseGoals(root.getAsJsonArray("goals"));
        List<IQuestCondition> conditions = parseConditions(root.getAsJsonArray("conditions"));
        List<IQuestCondition> failConditions = parseConditions(root.getAsJsonArray("failConditions"));
        List<QuestDefinition> children = parseChildren(root.getAsJsonArray("children"), namespace);
        Vec3 location = parseLocation(root.get("location"));
        float priority = root.has("priority") ? root.get("priority").getAsFloat() : 0f;

        LogManager.serverInfo(TAG, "Parsed quest '{}': goals={}, givers={}, children={}",
                id, goals.size(), givers.size(), children.size());
        return new QuestDefinition(id, title, description,
                givers, goals, conditions, failConditions, children, location, priority);
    }

    // ========== 组件解析（经 QuestSystem 工厂注册表） ==========

    private static List<IQuestGiver> parseGivers(JsonArray arr) {
        List<IQuestGiver> result = new ArrayList<>();
        if (arr == null) {
            return result;
        }
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String type = obj.get("type").getAsString();
            var factory = QuestSystem.getInstance().getGiverFactory(type);
            if (factory.isPresent()) {
                result.add(factory.get().apply(obj));
            } else {
                LogManager.serverWarn(TAG, "Unknown giver type '{}' (quest component)", type);
            }
        }
        return result;
    }

    private static List<IQuestGoal> parseGoals(JsonArray arr) {
        List<IQuestGoal> result = new ArrayList<>();
        if (arr == null) {
            return result;
        }
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String type = obj.get("type").getAsString();
            var factory = QuestSystem.getInstance().getGoalFactory(type);
            if (factory.isPresent()) {
                result.add(factory.get().apply(obj));
            } else {
                LogManager.serverWarn(TAG, "Unknown goal type '{}' (quest component)", type);
            }
        }
        return result;
    }

    private static List<IQuestCondition> parseConditions(JsonArray arr) {
        List<IQuestCondition> result = new ArrayList<>();
        if (arr == null) {
            return result;
        }
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String type = obj.get("type").getAsString();
            var factory = QuestSystem.getInstance().getConditionFactory(type);
            if (factory.isPresent()) {
                result.add(factory.get().apply(obj));
            } else {
                LogManager.serverWarn(TAG, "Unknown condition type '{}' (quest component)", type);
            }
        }
        return result;
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

    private static Vec3 parseLocation(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return null;
        }
        JsonArray arr = el.getAsJsonArray();
        return new Vec3(arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble());
    }
}
