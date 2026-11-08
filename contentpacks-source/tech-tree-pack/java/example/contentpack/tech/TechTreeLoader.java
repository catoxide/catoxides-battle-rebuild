package example.contentpack.tech;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 读取 {@code techs/tree.json} 并做结构自检。
 *
 * <p>自检失败一律 {@code LogManager.serverError} —— validate 脚本按 ERROR 标记抓问题。
 * 结构错误不阻止树加载：能展示的节点照常展示，坏节点已在解析阶段隔离。
 */
public final class TechTreeLoader {

    private static final String TAG = "TechTree";
    private static final String PATH = "techs/tree.json";
    private static final ResourceLocation DEFAULT_ICON = ResourceLocation.parse("minecraft:paper");
    private static final String ID_PATTERN = "[a-z0-9_.\\-]+";

    private static TechTree cached;

    private TechTreeLoader() {
    }

    /** 包 jar 根目录下的树数据；解析一次后缓存（两端各自解析自己的包）。 */
    public static synchronized TechTree load() {
        if (cached == null) {
            cached = parse();
        }
        return cached;
    }

    private static TechTree parse() {
        try (InputStream in = TechTreeLoader.class.getClassLoader().getResourceAsStream(PATH)) {
            if (in == null) {
                LogManager.serverError(TAG, "缺少 %s（应位于包 jar 根目录）", PATH);
                return TechTree.EMPTY;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            String title = root.has("title") ? root.get("title").getAsString() : "Tech Tree";
            if (!root.has("nodes") || !root.get("nodes").isJsonArray()) {
                LogManager.serverError(TAG, "%s 缺少 nodes 数组", PATH);
                return new TechTree(title, TechTree.TableSpec.DEFAULT, TechTree.PointsSpec.DEFAULT,
                        List.of(TechTree.DEFAULT_PAGE), List.of(), List.of());
            }

            TechTree.TableSpec table = parseTable(root.get("table"));
            TechTree.PointsSpec points = parsePoints(root.get("points"));
            List<TechTree.Page> pages = parsePages(root.get("pages"));
            List<TechNode> nodes = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("nodes")) {
                try {
                    nodes.add(parseNode(element.getAsJsonObject(), pages));
                } catch (Exception e) {
                    LogManager.serverError(TAG, "节点解析失败: %s", e.getMessage());
                }
            }
            List<TechTree.Research> research = new ArrayList<>();
            if (root.has("research") && root.get("research").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("research")) {
                    try {
                        research.add(parseResearch(element.getAsJsonObject(), pages,
                                points.questReward()));
                    } catch (Exception e) {
                        LogManager.serverError(TAG, "研究项解析失败: %s", e.getMessage());
                    }
                }
            }
            pages = completePages(pages, nodes, research);

            List<String> problems = validate(nodes, research, table, pages);
            for (String problem : problems) {
                LogManager.serverError(TAG, "%s", problem);
            }
            LogManager.serverInfo(TAG, "loaded %d node(s), %d research item(s), %d page(s) "
                            + "from %s (table maxTier=%d, questReward=%d, %d problem(s))",
                    nodes.size(), research.size(), pages.size(), PATH,
                    table.maxTier(), points.questReward(), problems.size());
            return new TechTree(title, table, points, pages, research, nodes);
        } catch (Exception e) {
            LogManager.serverError(TAG, "解析 " + PATH + " 失败: " + e.getMessage(), e);
            return TechTree.EMPTY;
        }
    }

    // ==================== 页签 ====================

    private static List<TechTree.Page> parsePages(JsonElement element) {
        List<TechTree.Page> pages = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return pages;
        }
        for (JsonElement entry : element.getAsJsonArray()) {
            JsonObject json = entry.getAsJsonObject();
            String id = required(json, "id");
            ResourceLocation icon = json.has("icon")
                    ? ResourceLocation.parse(json.get("icon").getAsString()) : DEFAULT_ICON;
            pages.add(new TechTree.Page(id,
                    json.has("title") ? json.get("title").getAsString() : id, icon));
        }
        return pages;
    }

    /** 补齐节点/研究项用到但未声明的页签，保证不会丢内容。 */
    private static List<TechTree.Page> completePages(List<TechTree.Page> pages,
                                                     List<TechNode> nodes,
                                                     List<TechTree.Research> research) {
        Map<String, TechTree.Page> byId = new LinkedHashMap<>();
        for (TechTree.Page page : pages) {
            byId.put(page.id(), page);
        }
        Set<String> used = new LinkedHashSet<>();
        for (TechNode node : nodes) {
            used.add(node.page());
        }
        for (TechTree.Research entry : research) {
            used.add(entry.page());
        }
        for (String pageId : used) {
            byId.computeIfAbsent(pageId,
                    id -> new TechTree.Page(id, id, DEFAULT_ICON));
        }
        if (byId.isEmpty()) {
            byId.put(TechTree.DEFAULT_PAGE.id(), TechTree.DEFAULT_PAGE);
        }
        return List.copyOf(byId.values());
    }

    private static String pageOrDefault(String raw, List<TechTree.Page> pages) {
        if (raw != null && !raw.isEmpty()) {
            return raw;
        }
        return pages.isEmpty() ? TechTree.DEFAULT_PAGE.id() : pages.get(0).id();
    }

    // ==================== 节点 ====================

    private static TechNode parseNode(JsonObject json, List<TechTree.Page> pages) {
        String id = required(json, "id");
        String title = json.has("title") ? json.get("title").getAsString() : id;
        String desc = json.has("desc") ? json.get("desc").getAsString() : "";
        String page = pageOrDefault(json.has("page") ? json.get("page").getAsString() : null, pages);
        int col = json.has("col") ? json.get("col").getAsInt() : 0;
        int row = json.has("row") ? json.get("row").getAsInt() : 0;
        ResourceLocation icon = ResourceLocation.parse(required(json, "icon"));

        List<TechRequirement> requirements = parseRequirements(json.get("requirements"));
        // 旧字段兼容：tableTier / requires 合并为条件
        int legacyTier = json.has("tableTier") ? json.get("tableTier").getAsInt() : 1;
        if (legacyTier > 1) {
            JsonObject params = new JsonObject();
            params.addProperty("min", legacyTier);
            requirements.add(0, new TechRequirement("table_tier", params));
        }
        List<String> legacyRequires = strings(json, "requires");
        if (!legacyRequires.isEmpty()) {
            requirements.add(0, techRequirement(legacyRequires));
        }

        List<TechCost> costs = parseCosts(json.get("costs"));
        List<TechNode.Reward> rewards = new ArrayList<>();
        if (json.has("rewards")) {
            for (JsonElement element : json.getAsJsonArray("rewards")) {
                JsonObject reward = element.getAsJsonObject();
                rewards.add(new TechNode.Reward(
                        ResourceLocation.parse(required(reward, "item")),
                        reward.has("count") ? reward.get("count").getAsInt() : 1));
            }
        }
        boolean quest = json.has("quest") && json.get("quest").getAsBoolean();
        List<TechRequirement> visibleWhen = parseRequirements(json.get("visibleWhen"));
        int resetAfterTicks = json.has("resetAfterTicks") ? json.get("resetAfterTicks").getAsInt() : 0;
        List<TechRequirement> resetWhen = parseRequirements(json.get("resetWhen"));
        List<String> unlockTriggers = strings(json, "onUnlock");
        return new TechNode(id, title, desc, page, col, row, icon, requirements, costs,
                rewards, quest, visibleWhen, Math.max(0, resetAfterTicks), resetWhen, unlockTriggers);
    }

    // ==================== 研究项 ====================

    private static TechTree.Research parseResearch(JsonObject json, List<TechTree.Page> pages,
                                                   int defaultPoints) {
        String id = required(json, "id");
        String page = pageOrDefault(json.has("page") ? json.get("page").getAsString() : null, pages);
        ResourceLocation icon = json.has("icon")
                ? ResourceLocation.parse(json.get("icon").getAsString()) : DEFAULT_ICON;
        List<TechRequirement> requirements = parseRequirements(json.get("requirements"));
        List<String> legacyRequires = strings(json, "requires");
        if (!legacyRequires.isEmpty()) {
            requirements.add(0, techRequirement(legacyRequires));
        }
        return new TechTree.Research(
                id,
                json.has("title") ? json.get("title").getAsString() : id,
                json.has("desc") ? json.get("desc").getAsString() : "",
                page,
                icon,
                requirements,
                parseRequirements(json.get("visibleWhen")),
                parseCosts(json.get("costs")),
                json.has("points") ? json.get("points").getAsInt() : defaultPoints,
                json.has("cooldownTicks") ? json.get("cooldownTicks").getAsInt() : 0);
    }

    // ==================== 条件 ====================

    private static List<TechRequirement> parseRequirements(JsonElement element) {
        List<TechRequirement> out = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return out;
        }
        for (JsonElement entry : element.getAsJsonArray()) {
            JsonObject object = entry.getAsJsonObject();
            out.add(new TechRequirement(required(object, "type"), object));
        }
        return out;
    }

    private static TechRequirement techRequirement(List<String> nodeIds) {
        JsonObject params = new JsonObject();
        var array = new com.google.gson.JsonArray();
        nodeIds.forEach(array::add);
        params.add("nodes", array);
        return new TechRequirement("tech", params);
    }

    // ==================== 研究台 / 成本 ====================

    private static TechTree.TableSpec parseTable(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return TechTree.TableSpec.DEFAULT;
        }
        JsonObject json = element.getAsJsonObject();
        int maxTier = json.has("maxTier") ? json.get("maxTier").getAsInt() : 1;
        List<TechTree.TableSpec.Upgrade> upgrades = new ArrayList<>();
        if (json.has("upgrades")) {
            for (JsonElement entry : json.getAsJsonArray("upgrades")) {
                JsonObject upgrade = entry.getAsJsonObject();
                upgrades.add(new TechTree.TableSpec.Upgrade(
                        upgrade.get("to").getAsInt(),
                        parseCosts(upgrade.get("costs"))));
            }
        }
        return new TechTree.TableSpec(maxTier, upgrades);
    }

    private static TechTree.PointsSpec parsePoints(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return TechTree.PointsSpec.DEFAULT;
        }
        JsonObject json = element.getAsJsonObject();
        int questReward = json.has("questReward")
                ? json.get("questReward").getAsInt()
                : TechTree.PointsSpec.DEFAULT.questReward();
        return new TechTree.PointsSpec(questReward);
    }

    private static List<TechCost> parseCosts(JsonElement element) {
        List<TechCost> costs = new ArrayList<>();
        if (element != null && !element.isJsonNull()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                costs.add(parseCost(entry.getAsJsonObject()));
            }
        }
        return costs;
    }

    private static TechCost parseCost(JsonObject json) {
        String type = required(json, "type");
        int amount = json.has("amount") ? json.get("amount").getAsInt() : 1;
        return switch (type) {
            case "item" -> new TechCost.ItemCost(
                    ResourceLocation.parse(required(json, "item")), amount);
            case "xp_levels" -> new TechCost.XpCost(amount);
            case "tech_point" -> new TechCost.TechPointCost(amount);
            default -> throw new IllegalArgumentException("未知成本类型: " + type);
        };
    }

    // ==================== 自检 ====================

    private static List<String> validate(List<TechNode> nodes, List<TechTree.Research> research,
                                         TechTree.TableSpec table, List<TechTree.Page> pages) {
        List<String> problems = new ArrayList<>();
        Set<String> pageIds = new HashSet<>();
        for (TechTree.Page page : pages) {
            if (!page.id().matches(ID_PATTERN)) {
                problems.add("页签 id 非法: " + page.id());
            }
            if (!pageIds.add(page.id())) {
                problems.add("重复页签 id: " + page.id());
            }
        }

        Map<String, TechNode> byId = new LinkedHashMap<>();
        for (TechNode node : nodes) {
            if (!node.id().matches(ID_PATTERN)) {
                problems.add("节点 id 非法（只允许小写字母/数字/_.-）: " + node.id());
            }
            if (byId.putIfAbsent(node.id(), node) != null) {
                problems.add("重复节点 id: " + node.id());
            }
            if (!pageIds.contains(node.page())) {
                problems.add("节点 " + node.id() + " 的页签不存在: " + node.page());
            }
        }
        Set<String> researchIds = new HashSet<>();
        for (TechTree.Research entry : research) {
            if (!entry.id().matches(ID_PATTERN)) {
                problems.add("研究项 id 非法（只允许小写字母/数字/_.-）: " + entry.id());
            }
            if (!researchIds.add(entry.id())) {
                problems.add("重复研究项 id: " + entry.id());
            }
            if (!pageIds.contains(entry.page())) {
                problems.add("研究项 " + entry.id() + " 的页签不存在: " + entry.page());
            }
            if (entry.costs().isEmpty()) {
                problems.add("研究项 " + entry.id() + " 缺少 costs（必须消耗点什么）");
            }
            for (TechCost cost : entry.costs()) {
                if (cost.amount() <= 0) {
                    problems.add("研究项 " + entry.id() + " 的成本必须 > 0");
                }
            }
        }

        for (TechNode node : nodes) {
            for (TechRequirement requirement : node.requirements()) {
                validateRequirement("节点 " + node.id(), requirement, byId, problems);
            }
            for (TechRequirement requirement : node.visibleWhen()) {
                validateRequirement("节点 " + node.id() + " 的 visibleWhen", requirement, byId, problems);
            }
            for (TechRequirement requirement : node.resetWhen()) {
                validateRequirement("节点 " + node.id() + " 的 resetWhen", requirement, byId, problems);
            }
            for (TechCost cost : node.costs()) {
                if (cost.amount() <= 0) {
                    problems.add("节点 " + node.id() + " 的成本必须 > 0");
                }
            }
            for (TechNode.Reward reward : node.rewards()) {
                if (reward.count() <= 0) {
                    problems.add("节点 " + node.id() + " 的奖励数量必须 > 0");
                }
            }
        }
        for (TechTree.Research entry : research) {
            for (TechRequirement requirement : entry.requirements()) {
                validateRequirement("研究项 " + entry.id(), requirement, byId, problems);
            }
            for (TechRequirement requirement : entry.visibleWhen()) {
                validateRequirement("研究项 " + entry.id() + " 的 visibleWhen", requirement, byId, problems);
            }
        }

        Set<Integer> targetTiers = new HashSet<>();
        for (TechTree.TableSpec.Upgrade upgrade : table.upgrades()) {
            if (upgrade.to() < 2 || upgrade.to() > table.maxTier()) {
                problems.add("研究台升级目标层级越界: " + upgrade.to());
            }
            if (!targetTiers.add(upgrade.to())) {
                problems.add("研究台升级目标层级重复: " + upgrade.to());
            }
            if (upgrade.costs().isEmpty()) {
                problems.add("研究台升级到 " + upgrade.to() + " 缺少成本");
            }
            for (TechCost cost : upgrade.costs()) {
                if (cost.amount() <= 0) {
                    problems.add("研究台升级到 " + upgrade.to() + " 的成本必须 > 0");
                }
            }
        }
        for (int tier = 1; tier < table.maxTier(); tier++) {
            if (table.upgradeFrom(tier) == null) {
                problems.add("研究台缺少 " + tier + " → " + (tier + 1) + " 的升级项");
            }
        }

        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (TechNode node : nodes) {
            if (hasCycle(node.id(), byId, visiting, done)) {
                problems.add("条件依赖存在环（涉及节点 " + node.id() + "）");
                break;
            }
        }
        return problems;
    }

    private static void validateRequirement(String owner, TechRequirement requirement,
                                            Map<String, TechNode> byId, List<String> problems) {
        if (requirement.type() == null || requirement.type().isEmpty()) {
            problems.add(owner + " 的条件缺少 type");
            return;
        }
        switch (requirement.type()) {
            case "all", "any" -> {
                for (TechRequirement child : requirement.children()) {
                    validateRequirement(owner, child, byId, problems);
                }
            }
            case "tech" -> {
                for (String nodeId : requirement.strings("nodes")) {
                    if (!byId.containsKey(nodeId)) {
                        problems.add(owner + " 的条件引用了不存在的节点: " + nodeId);
                    }
                }
            }
            case "item" -> {
                String raw = requirement.string("item", "");
                ResourceLocation id = ResourceLocation.tryParse(raw);
                if (id == null || TechItems.resolve(id) == null) {
                    problems.add(owner + " 的物品条件解析失败: " + raw);
                }
            }
            case "table_tier" -> {
                if (requirement.integer("min", 1) < 1) {
                    problems.add(owner + " 的 table_tier 必须 ≥ 1");
                }
            }
            case "tech_points" -> {
                if (requirement.integer("min", 1) < 1) {
                    problems.add(owner + " 的 tech_points 必须 ≥ 1");
                }
            }
            default -> {
                // quest/advancement/trigger/all/any 与第三方自定义类型：运行时判定，不在此静态校验
            }
        }
    }

    private static boolean hasCycle(String id, Map<String, TechNode> byId,
                                    Set<String> visiting, Set<String> done) {
        if (done.contains(id)) {
            return false;
        }
        if (!visiting.add(id)) {
            return true;
        }
        TechNode node = byId.get(id);
        if (node != null) {
            List<String> prerequisites = new ArrayList<>();
            collectTechIds(node.requirements(), prerequisites);
            for (String require : prerequisites) {
                if (hasCycle(require, byId, visiting, done)) {
                    return true;
                }
            }
        }
        visiting.remove(id);
        done.add(id);
        return false;
    }

    /** 递归收集条件里引用的节点 id（含 all/any 组合）。 */
    private static void collectTechIds(List<TechRequirement> requirements, List<String> out) {
        for (TechRequirement requirement : requirements) {
            if ("tech".equals(requirement.type())) {
                out.addAll(requirement.strings("nodes"));
            } else if ("all".equals(requirement.type()) || "any".equals(requirement.type())) {
                collectTechIds(requirement.children(), out);
            }
        }
    }

    private static String required(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            throw new IllegalArgumentException("缺少字段 " + key);
        }
        return json.get(key).getAsString();
    }

    private static List<String> strings(JsonObject json, String key) {
        List<String> out = new ArrayList<>();
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return out;
        }
        for (JsonElement element : json.getAsJsonArray(key)) {
            out.add(element.getAsString());
        }
        return out;
    }
}
