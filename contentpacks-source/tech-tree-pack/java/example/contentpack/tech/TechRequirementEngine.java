package example.contentpack.tech;

import com.catoxide.catoxidesbattlerebuild.core.tech.TechHooks;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据驱动条件引擎（服务端判定；说明文本两端共用）。
 *
 * <p>内置类型：
 * <ul>
 *   <li>{@code table_tier} {@code {"min":2}} —— 当前研究台层级 ≥ min</li>
 *   <li>{@code tech} {@code {"nodes":["a","b"]}} —— 这些节点均已解锁</li>
 *   <li>{@code quest} {@code {"quests":["pack:id"]}} —— 这些任务已完成</li>
 *   <li>{@code item} {@code {"item":"minecraft:x","count":8,"consume":false}} —— 持有数量</li>
 *   <li>{@code advancement} {@code {"id":"minecraft:story/mine_stone"}} —— 已完成进度</li>
 *   <li>{@code trigger} {@code {"name":"found_ruins"}} —— 自定义触发器</li>
 *   <li>{@code tech_points} {@code {"min":3}} —— 科技点余额</li>
 *   <li>{@code all} / {@code any} {@code {"requirements":[...]}} —— 组合</li>
 * </ul>
 * 其它类型由其它内容包通过 {@link TechHooks#REQUIREMENTS} capability 注册。
 */
public final class TechRequirementEngine {

    private static final Map<String, TechHooks.RequirementExtension> EXTENSIONS = new HashMap<>();

    private TechRequirementEngine() {
    }

    /** 解锁判定上下文（研究台位置与层级）。 */
    public record Context(BlockPos tablePos, int tableTier) {
        public static final Context NONE = new Context(null, 1);
    }

    /** capability 注册器：其它包注册自定义条件类型。 */
    public static void registerExtension(TechHooks.RequirementExtension extension) {
        EXTENSIONS.put(extension.type(), extension);
    }

    public static boolean satisfied(ServerPlayer player, List<TechRequirement> requirements, Context context) {
        for (TechRequirement requirement : requirements) {
            if (!satisfied(player, requirement, context)) {
                return false;
            }
        }
        return true;
    }

    public static boolean satisfied(ServerPlayer player, TechRequirement requirement, Context context) {
        String type = requirement.type() == null ? "" : requirement.type();
        return switch (type) {
            case "all" -> satisfied(player, requirement.children(), context);
            case "any" -> {
                List<TechRequirement> children = requirement.children();
                if (children.isEmpty()) {
                    yield true;
                }
                boolean any = false;
                for (TechRequirement child : children) {
                    if (satisfied(player, child, context)) {
                        any = true;
                        break;
                    }
                }
                yield any;
            }
            case "table_tier" -> context.tableTier() >= requirement.integer("min", 1);
            case "tech" -> {
                boolean all = true;
                for (String node : requirement.strings("nodes")) {
                    if (!TechService.isUnlocked(player, node)) {
                        all = false;
                        break;
                    }
                }
                yield all;
            }
            case "quest" -> {
                boolean all = true;
                for (String quest : requirement.strings("quests")) {
                    if (!TechService.hasCompletedQuest(player, quest)) {
                        all = false;
                        break;
                    }
                }
                yield all;
            }
            case "item" -> {
                Item item = TechItems.resolve(ResourceLocation.parse(requirement.string("item", "")));
                yield item != null && TechCost.count(player, item) >= requirement.integer("count", 1);
            }
            case "advancement" -> hasAdvancement(player, requirement.string("id", ""));
            case "trigger" -> TechService.hasTrigger(player, requirement.string("name", ""));
            case "tech_points" -> TechService.points(player) >= requirement.integer("min", 1);
            default -> {
                TechHooks.RequirementExtension extension = EXTENSIONS.get(type);
                yield extension != null && extension.satisfied(player, requirement.params());
            }
        };
    }

    /** 条件说明（UI/提示用；不依赖玩家）。 */
    public static Component describe(TechRequirement requirement) {
        String type = requirement.type() == null ? "" : requirement.type();
        return switch (type) {
            case "all", "any" -> {
                StringBuilder text = new StringBuilder();
                String sep = "any".equals(type) ? " / " : " + ";
                for (TechRequirement child : requirement.children()) {
                    if (text.length() > 0) {
                        text.append(sep);
                    }
                    text.append(describe(child).getString());
                }
                yield Component.literal(text.toString());
            }
            case "table_tier" -> Component.translatable("tech_tree_pack.req.table_tier",
                    requirement.integer("min", 1));
            case "tech" -> Component.translatable("tech_tree_pack.req.tech",
                    joinTitles(requirement.strings("nodes")));
            case "quest" -> Component.translatable("tech_tree_pack.req.quest",
                    String.join(", ", requirement.strings("quests")));
            case "item" -> Component.translatable("tech_tree_pack.req.item",
                    itemName(requirement.string("item", "")), requirement.integer("count", 1));
            case "advancement" -> Component.translatable("tech_tree_pack.req.advancement",
                    requirement.string("id", ""));
            case "trigger" -> Component.translatable("tech_tree_pack.req.trigger",
                    requirement.string("name", ""));
            case "tech_points" -> Component.translatable("tech_tree_pack.req.tech_points",
                    requirement.integer("min", 1));
            default -> {
                TechHooks.RequirementExtension extension = EXTENSIONS.get(type);
                yield Component.literal(extension != null
                        ? extension.describe(requirement.params())
                        : type);
            }
        };
    }

    private static boolean hasAdvancement(ServerPlayer player, String rawId) {
        if (rawId == null || rawId.isEmpty() || player.getServer() == null) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null) {
            return false;
        }
        AdvancementHolder holder = player.getServer().getAdvancements().get(id);
        return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static String joinTitles(List<String> nodeIds) {
        StringBuilder text = new StringBuilder();
        for (String nodeId : nodeIds) {
            if (text.length() > 0) {
                text.append(", ");
            }
            TechNode node = TechService.tree().node(nodeId);
            text.append(node != null ? node.title() : nodeId);
        }
        return text.toString();
    }

    private static String itemName(String rawId) {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        Item item = id == null ? null : TechItems.resolve(id);
        return item == null ? rawId : new ItemStack(item).getHoverName().getString();
    }
}
