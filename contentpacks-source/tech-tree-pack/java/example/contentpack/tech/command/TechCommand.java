package example.contentpack.tech.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import example.contentpack.tech.TechBlocks;
import example.contentpack.tech.TechNode;
import example.contentpack.tech.TechService;
import example.contentpack.tech.TechTree;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import example.contentpack.tech.network.TechTableSyncPayload;

/**
 * 科技树调试/管理命令（包内自定义，暂无全局命令规范）。
 *
 * <pre>
 * /techtree nodes [target]                 列出节点状态（可见/条件/解锁）
 * /techtree unlock &lt;node&gt; [target]         强制解锁
 * /techtree lock &lt;node&gt; [target]           强制回退
 * /techtree points [target]                查看科技点
 * /techtree points set &lt;n&gt; [target]        设置科技点
 * /techtree points add &lt;n&gt; [target]        增加科技点
 * /techtree trigger &lt;name&gt; [target]        写入自定义触发器
 * /techtree untrigger &lt;name&gt; [target]      清除自定义触发器
 * /techtree submit &lt;research&gt; [target]     提交研究项（需先打开过研究台）
 * /techtree tier &lt;n&gt;                       把附近的研究台层级设为 n
 * </pre>
 * 权限：2（op）。target 缺省 = 执行者。
 */
public final class TechCommand {

    private static final SuggestionProvider<CommandSourceStack> NODE_SUGGESTIONS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    TechService.tree().nodes().stream().map(TechNode::id).toList(), builder);

    private static final SuggestionProvider<CommandSourceStack> RESEARCH_SUGGESTIONS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    TechService.tree().research().stream().map(TechTree.Research::id).toList(), builder);

    private TechCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("techtree")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("nodes")
                        .executes(ctx -> listNodes(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> listNodes(ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "target")))))
                .then(Commands.literal("unlock")
                        .then(Commands.argument("node", StringArgumentType.word())
                                .suggests(NODE_SUGGESTIONS)
                                .executes(ctx -> unlock(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "node"),
                                        ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> unlock(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "node"),
                                                EntityArgument.getPlayer(ctx, "target"))))))
                .then(Commands.literal("lock")
                        .then(Commands.argument("node", StringArgumentType.word())
                                .suggests(NODE_SUGGESTIONS)
                                .executes(ctx -> lock(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "node"),
                                        ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> lock(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "node"),
                                                EntityArgument.getPlayer(ctx, "target"))))))
                .then(Commands.literal("points")
                        .executes(ctx -> showPoints(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> showPoints(ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "target"))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                        .executes(ctx -> setPoints(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "value"),
                                                ctx.getSource().getPlayerOrException()))
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .executes(ctx -> setPoints(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "value"),
                                                        EntityArgument.getPlayer(ctx, "target"))))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("value", IntegerArgumentType.integer())
                                        .executes(ctx -> addPoints(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "value"),
                                                ctx.getSource().getPlayerOrException()))
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .executes(ctx -> addPoints(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "value"),
                                                        EntityArgument.getPlayer(ctx, "target")))))))
                .then(Commands.literal("trigger")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> setTrigger(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"), true,
                                        ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> setTrigger(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), true,
                                                EntityArgument.getPlayer(ctx, "target"))))))
                .then(Commands.literal("untrigger")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> setTrigger(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"), false,
                                        ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> setTrigger(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), false,
                                                EntityArgument.getPlayer(ctx, "target"))))))
                .then(Commands.literal("submit")
                        .then(Commands.argument("research", StringArgumentType.word())
                                .suggests(RESEARCH_SUGGESTIONS)
                                .executes(ctx -> submit(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "research"),
                                        ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> submit(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "research"),
                                                EntityArgument.getPlayer(ctx, "target"))))))
                .then(Commands.literal("tier")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1))
                                .executes(ctx -> setTier(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "value")))))
        );
    }

    // ==================== 执行体 ====================

    private static int listNodes(CommandSourceStack source, ServerPlayer player) {
        ListSnapshot snapshot = snapshot(player);
        source.sendSuccess(() -> Component.literal("科技节点 " + snapshot.total() + " 个，"
                + "可见 " + snapshot.visible() + "，条件满足 " + snapshot.met()
                + "，已解锁 " + snapshot.unlocked()), false);
        for (TechNode node : TechService.tree().nodes()) {
            String state = TechService.isUnlocked(player, node.id()) ? "§a解锁"
                    : snapshot.metIds().contains(node.id()) ? "§e可解锁" : "§7未满足";
            String visibility = snapshot.visibleIds().contains(node.id()) ? "" : " §8(隐藏)";
            source.sendSuccess(() -> Component.literal(" - " + node.id()
                    + " §f" + node.title() + " " + state + visibility), false);
        }
        return snapshot.total();
    }

    private static int unlock(CommandSourceStack source, String nodeId, ServerPlayer target) {
        if (TechService.tree().node(nodeId) == null) {
            source.sendFailure(Component.literal("未知科技节点: " + nodeId));
            return 0;
        }
        if (!TechService.hasBoundTable(target)) {
            source.sendFailure(Component.literal(noTableMessage(target)));
            return 0;
        }
        if (!TechService.forceUnlock(target, nodeId)) {
            source.sendFailure(Component.literal("解锁失败: " + nodeId));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已解锁 " + nodeId
                + "（" + target.getScoreboardName() + "）"), true);
        return 1;
    }

    private static int lock(CommandSourceStack source, String nodeId, ServerPlayer target) {
        if (!TechService.hasBoundTable(target)) {
            source.sendFailure(Component.literal(noTableMessage(target)));
            return 0;
        }
        if (!TechService.forceLock(target, nodeId)) {
            source.sendFailure(Component.literal("该玩家未解锁此节点: " + nodeId));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已回退 " + nodeId
                + "（" + target.getScoreboardName() + "）"), true);
        return 1;
    }

    private static int showPoints(CommandSourceStack source, ServerPlayer target) {
        if (!TechService.hasBoundTable(target)) {
            source.sendFailure(Component.literal(noTableMessage(target)));
            return 0;
        }
        int balance = TechService.points(target);
        source.sendSuccess(() -> Component.literal(target.getScoreboardName()
                + " 当前研究台的科技点: " + balance), false);
        return balance;
    }

    private static int setPoints(CommandSourceStack source, int value, ServerPlayer target) {
        if (!TechService.hasBoundTable(target)) {
            source.sendFailure(Component.literal(noTableMessage(target)));
            return 0;
        }
        int updated = TechService.setPoints(target, value);
        source.sendSuccess(() -> Component.literal("科技点已设为 " + updated
                + "（" + target.getScoreboardName() + "）"), true);
        return updated;
    }

    private static int addPoints(CommandSourceStack source, int value, ServerPlayer target) {
        if (!TechService.hasBoundTable(target)) {
            source.sendFailure(Component.literal(noTableMessage(target)));
            return 0;
        }
        int updated = TechService.addPoints(target, value);
        source.sendSuccess(() -> Component.literal("科技点 " + (value >= 0 ? "+" : "")
                + value + " → " + updated + "（" + target.getScoreboardName() + "）"), true);
        return updated;
    }

    private static String noTableMessage(ServerPlayer target) {
        return "该玩家还没打开过研究台，进度与科技点归属台子，先在台子旁打开一次界面"
                + (target == null ? "" : "（" + target.getScoreboardName() + "）");
    }

    private static int setTrigger(CommandSourceStack source, String name, boolean value,
                                  ServerPlayer target) {
        TechService.setTrigger(target, name, value);
        source.sendSuccess(() -> Component.literal("触发器 " + name
                + (value ? " 已置位" : " 已清除") + "（" + target.getScoreboardName() + "）"), true);
        return 1;
    }

    private static int submit(CommandSourceStack source, String researchId, ServerPlayer target) {
        if (TechService.tree().research(researchId) == null) {
            source.sendFailure(Component.literal("未知研究项: " + researchId));
            return 0;
        }
        BlockPos tablePos = TechService.contextOf(target).tablePos();
        if (tablePos == null) {
            source.sendFailure(Component.literal("该玩家还没打开过研究台，先在台子旁打开一次界面"));
            return 0;
        }
        TechService.SubmitResult result = TechService.submitResearch(target, researchId, tablePos);
        if (result != TechService.SubmitResult.OK) {
            source.sendFailure(Component.literal("提交失败: " + result));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已提交 " + researchId
                + "（" + target.getScoreboardName() + "）"), true);
        return 1;
    }

    private static int setTier(CommandSourceStack source, int tier) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BlockPos pos = findTable(player, 8);
        if (pos == null) {
            source.sendFailure(Component.literal("附近 8 格内没有研究台"));
            return 0;
        }
        if (!TechService.setTableTier(player, pos, tier)) {
            source.sendFailure(Component.literal("设置层级失败（该层级不存在或已是该层级）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("研究台 " + pos.toShortString()
                + " 已设为 " + TechBlocks.tierAt(player.serverLevel(), pos) + " 级"), true);
        return 1;
    }

    // ==================== 工具 ====================

    private static BlockPos findTable(ServerPlayer player, int radius) {
        BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (TechBlocks.tierAt(player.serverLevel(), cursor) <= 0) {
                        continue;
                    }
                    double dist = cursor.distSqr(origin);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    private record ListSnapshot(int total, int visible, int met, int unlocked,
                                java.util.Set<String> visibleIds,
                                java.util.Set<String> metIds,
                                java.util.Set<String> unlockedIds) {
    }

    private static ListSnapshot snapshot(ServerPlayer player) {
        var context = TechService.contextOf(player);
        var visible = TechService.visibleNodeIds(player, context);
        var met = TechService.metNodeIds(player, context);
        var unlocked = TechService.unlocked(player);
        return new ListSnapshot(TechService.tree().nodes().size(), visible.size(), met.size(),
                unlocked.size(), new java.util.HashSet<>(visible), new java.util.HashSet<>(met),
                new java.util.HashSet<>(unlocked));
    }
}
