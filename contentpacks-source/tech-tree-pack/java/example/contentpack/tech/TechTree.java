package example.contentpack.tech;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 科技树根数据。
 *
 * @param table    研究台层级与升级成本
 * @param points   科技点获取规则
 * @param pages    页签（任务书式的分页；节点/研究项按 {@code page} 归页）
 * @param research 可提交研究项（按钮提交 → 扣成本 → 发科技点，冷却后可再次提交）
 * @param nodes    节点（数量小，查找用线性扫描即可）
 */
public record TechTree(String title, TableSpec table, PointsSpec points, List<Page> pages,
                       List<Research> research, List<TechNode> nodes) {

    public static final Page DEFAULT_PAGE =
            new Page("main", "基础", ResourceLocation.parse("minecraft:book"));

    public static final TechTree EMPTY = new TechTree("", TableSpec.DEFAULT, PointsSpec.DEFAULT,
            List.of(DEFAULT_PAGE), List.of(), List.of());

    public TechTree {
        table = table == null ? TableSpec.DEFAULT : table;
        points = points == null ? PointsSpec.DEFAULT : points;
        pages = pages == null || pages.isEmpty() ? List.of(DEFAULT_PAGE) : List.copyOf(pages);
        research = List.copyOf(research);
        nodes = List.copyOf(nodes);
    }

    public TechNode node(String id) {
        for (TechNode node : nodes) {
            if (node.id().equals(id)) {
                return node;
            }
        }
        return null;
    }

    public Research research(String id) {
        for (Research entry : research) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    public Page page(String id) {
        for (Page page : pages) {
            if (page.id().equals(id)) {
                return page;
            }
        }
        return null;
    }

    /** 节点/研究项所属页签（未声明或页签不存在时归到首页）。 */
    public String pageOf(String pageId) {
        return page(pageId) != null ? pageId : pages.get(0).id();
    }

    /**
     * 科技点规则。
     *
     * @param questReward 任意普通任务完成时发放的科技点
     */
    public record PointsSpec(int questReward) {
        public static final PointsSpec DEFAULT = new PointsSpec(1);

        public PointsSpec {
            questReward = Math.max(0, questReward);
        }
    }

    /** 页签（任务书章节）。 */
    public record Page(String id, String title, ResourceLocation icon) {
    }

    /**
     * 可提交研究项：按按钮提交 → 扣 {@code costs} → 得 {@code points}，冷却后可再次提交。
     *
     * @param requirements 提交条件（不消耗）
     * @param costs        每次提交消耗（物品/经验等级）
     * @param visibleWhen  可见条件（空 = 始终可见）
     */
    public record Research(String id, String title, String desc, String page, ResourceLocation icon,
                           List<TechRequirement> requirements, List<TechRequirement> visibleWhen,
                           List<TechCost> costs, int points, int cooldownTicks) {
        public Research {
            requirements = List.copyOf(requirements);
            visibleWhen = List.copyOf(visibleWhen);
            costs = List.copyOf(costs);
            points = Math.max(0, points);
            cooldownTicks = Math.max(0, cooldownTicks);
        }
    }

    /**
     * 研究台升级规则（数据驱动）。
     *
     * @param maxTier  最高层级
     * @param upgrades 从当前层级到下一层级的成本（{@code to} = 目标层级）
     */
    public record TableSpec(int maxTier, List<Upgrade> upgrades) {

        public static final TableSpec DEFAULT = new TableSpec(1, List.of());

        public TableSpec {
            maxTier = Math.max(1, maxTier);
            upgrades = List.copyOf(upgrades);
        }

        /** 当前层级 → 下一层级的升级项；没有则 null。 */
        public Upgrade upgradeFrom(int currentTier) {
            for (Upgrade upgrade : upgrades) {
                if (upgrade.to() == currentTier + 1) {
                    return upgrade;
                }
            }
            return null;
        }

        public record Upgrade(int to, List<TechCost> costs) {
            public Upgrade {
                costs = List.copyOf(costs);
            }
        }
    }
}
