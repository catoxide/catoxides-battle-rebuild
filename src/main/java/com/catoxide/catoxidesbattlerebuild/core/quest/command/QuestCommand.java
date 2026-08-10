package com.catoxide.catoxidesbattlerebuild.core.quest.command;

import com.catoxide.catoxidesbattlerebuild.core.quest.QuestDefinition;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;

/**
 * 任务系统调试命令（参考 Mission_Core 的 /mission，适配 QuestSystem）。
 * <p>用法：
 * <pre>
 * /quest list                                  列出已注册任务定义
 * /quest player [target]                       列出玩家任务（状态/子任务进度）
 * /quest give &lt;definition_id&gt; [target]        发放任务
 * /quest complete &lt;definition_id&gt; [target]    强制完成
 * /quest fail &lt;definition_id&gt; [target]        强制失败
 * /quest remove &lt;definition_id&gt; [target]      消除任务
 * /quest progress &lt;definition_id&gt; &lt;key&gt; &lt;value&gt; [target]  设进度(触发完成检查)
 * /quest star &lt;definition_id&gt; [target]        星标切换
 * </pre>
 * 权限：2（op）。target 缺省 = 执行者。
 */
public final class QuestCommand {

    private static final String TAG = "QuestCommand";

    private QuestCommand() {
    }

    private static final SuggestionProvider<CommandSourceStack> QUEST_DEFINITION_SUGGESTIONS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    QuestSystem.getInstance().getDefinitions().stream()
                            .map(d -> d.id().toString())
                            .toList(),
                    builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("quest")
                .requires(src -> src.hasPermission(2))
                // /quest list
                .then(Commands.literal("list")
                        .executes(ctx -> listDefinitions(ctx.getSource())))
                // /quest player [target]
                .then(Commands.literal("player")
                        .executes(ctx -> listPlayerQuests(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> listPlayerQuests(ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "target")))))
                // /quest give <definition_id> [target]
                .then(Commands.literal("give")
                        .then(Commands.argument("definition_id", StringArgumentType.string())
                                .suggests(QUEST_DEFINITION_SUGGESTIONS)
                                .executes(ctx -> giveQuest(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "definition_id"),
                                        ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> giveQuest(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "definition_id"),
                                                EntityArgument.getPlayer(ctx, "target"))))))
                // /quest complete <definition_id> [target]
                .then(Commands.literal("complete")
                        .then(Commands.argument("definition_id", StringArgumentType.string())
                                .suggests(QUEST_DEFINITION_SUGGESTIONS)
                                .executes(ctx -> completeQuest(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "definition_id"),
                                        ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> completeQuest(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "definition_id"),
                                                EntityArgument.getPlayer(ctx, "target"))))))
                // /quest fail <definition_id> [target]
                .then(Commands.literal("fail")
                        .then(Commands.argument("definition_id", StringArgumentType.string())
                                .suggests(QUEST_DEFINITION_SUGGESTIONS)
                                .executes(ctx -> failQuest(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "definition_id"),
                                        ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> failQuest(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "definition_id"),
                                                EntityArgument.getPlayer(ctx, "target"))))))
                // /quest remove <definition_id> [target]
                .then(Commands.literal("remove")
                        .then(Commands.argument("definition_id", StringArgumentType.string())
                                .suggests(QUEST_DEFINITION_SUGGESTIONS)
                                .executes(ctx -> removeQuest(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "definition_id"),
                                        ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> removeQuest(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "definition_id"),
                                                EntityArgument.getPlayer(ctx, "target"))))))
                // /quest progress <definition_id> <key> <value> [target]
                .then(Commands.literal("progress")
                        .then(Commands.argument("definition_id", StringArgumentType.string())
                                .suggests(QUEST_DEFINITION_SUGGESTIONS)
                                .then(Commands.argument("key", StringArgumentType.string())
                                        .then(Commands.argument("value", FloatArgumentType.floatArg())
                                                .executes(ctx -> setProgress(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "definition_id"),
                                                        StringArgumentType.getString(ctx, "key"),
                                                        FloatArgumentType.getFloat(ctx, "value"),
                                                        ctx.getSource().getPlayerOrException()))
                                                .then(Commands.argument("target", EntityArgument.player())
                                                        .executes(ctx -> setProgress(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "definition_id"),
                                                                StringArgumentType.getString(ctx, "key"),
                                                                FloatArgumentType.getFloat(ctx, "value"),
                                                                EntityArgument.getPlayer(ctx, "target"))))))))
                // /quest star <definition_id> [target]
                .then(Commands.literal("star")
                        .then(Commands.argument("definition_id", StringArgumentType.string())
                                .suggests(QUEST_DEFINITION_SUGGESTIONS)
                                .executes(ctx -> toggleStar(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "definition_id"),
                                        ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> toggleStar(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "definition_id"),
                                                EntityArgument.getPlayer(ctx, "target"))))))
        );
    }

    // ========== 执行体 ==========

    private static int listDefinitions(CommandSourceStack source) {
        Collection<QuestDefinition> defs = QuestSystem.getInstance().getDefinitions();
        source.sendSuccess(() -> Component.literal("任务定义 (" + defs.size() + "):"), false);
        if (defs.isEmpty()) {
            source.sendSuccess(() -> Component.literal(" - (无已注册定义)"), false);
        } else {
            for (QuestDefinition d : defs) {
                String line = String.format(" - %s (%s, goals=%d, children=%d)",
                        d.title().getString(), d.id(), d.goals().size(), d.children().size());
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        return defs.size();
    }

    private static int listPlayerQuests(CommandSourceStack source, ServerPlayer player) {
        List<QuestInstance> quests = QuestSystem.getInstance().getPlayerQuests(player.getUUID());
        source.sendSuccess(() -> Component.literal("玩家 " + player.getScoreboardName()
                + " 的任务 (" + quests.size() + "):"), false);
        if (quests.isEmpty()) {
            source.sendSuccess(() -> Component.literal(" - (无任务)"), false);
            return 0;
        }
        for (QuestInstance q : quests) {
            QuestDefinition def = q.getDefinition();
            String line = String.format(" - %s (%s) [%s]%s",
                    def.title().getString(), def.id(), stateCn(q), starMark(q));
            if (!q.getChildInstances().isEmpty()) {
                long done = q.getChildInstances().stream().filter(QuestInstance::isCompleted).count();
                line += " 子任务 " + done + "/" + q.getChildInstances().size();
            }
            if (!q.getGoalProgress().isEmpty()) {
                line += " 进度 " + q.getGoalProgress();
            }
            final String out = line;
            source.sendSuccess(() -> Component.literal(out), false);
        }
        return quests.size();
    }

    private static int giveQuest(CommandSourceStack source, String definitionId, ServerPlayer target) {
        QuestInstance instance = QuestSystem.getInstance().giveQuest(target,
                net.minecraft.resources.ResourceLocation.parse(definitionId), "command");
        if (instance == null) {
            source.sendFailure(Component.literal("发放失败（未知定义或已发放）: " + definitionId));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已发放任务: "
                + instance.getDefinition().title().getString()), true);
        return 1;
    }

    private static int completeQuest(CommandSourceStack source, String definitionId, ServerPlayer target) {
        QuestInstance q = findActive(target, definitionId);
        if (q == null) {
            source.sendFailure(Component.literal("玩家没有激活的任务: " + definitionId));
            return 0;
        }
        if (QuestSystem.getInstance().completeQuest(q)) {
            source.sendSuccess(() -> Component.literal("已完成任务: " + q.getDefinition().title().getString()), true);
            return 1;
        }
        return 0;
    }

    private static int failQuest(CommandSourceStack source, String definitionId, ServerPlayer target) {
        QuestInstance q = findActive(target, definitionId);
        if (q == null) {
            source.sendFailure(Component.literal("玩家没有激活的任务: " + definitionId));
            return 0;
        }
        if (QuestSystem.getInstance().failQuest(q, "command")) {
            source.sendSuccess(() -> Component.literal("已失败任务: " + q.getDefinition().title().getString()), true);
            return 1;
        }
        return 0;
    }

    private static int removeQuest(CommandSourceStack source, String definitionId, ServerPlayer target) {
        QuestInstance q = QuestSystem.getInstance().getPlayerQuests(target.getUUID()).stream()
                .filter(i -> i.getDefinition().id().toString().equals(definitionId))
                .findFirst().orElse(null);
        if (q == null) {
            source.sendFailure(Component.literal("玩家没有该任务: " + definitionId));
            return 0;
        }
        QuestSystem.getInstance().removeQuest(q);
        source.sendSuccess(() -> Component.literal("已消除任务: " + q.getDefinition().title().getString()), true);
        return 1;
    }

    private static int setProgress(CommandSourceStack source, String definitionId,
                                   String key, float value, ServerPlayer target) {
        QuestInstance q = findActive(target, definitionId);
        if (q == null) {
            source.sendFailure(Component.literal("玩家没有激活的任务: " + definitionId));
            return 0;
        }
        QuestSystem.getInstance().updateProgress(q, key, value);
        source.sendSuccess(() -> Component.literal("已设置进度 " + key + "=" + value + "（触发完成检查）: "
                + q.getDefinition().title().getString()), true);
        return 1;
    }

    private static int toggleStar(CommandSourceStack source, String definitionId, ServerPlayer target) {
        QuestInstance q = QuestSystem.getInstance().getPlayerQuests(target.getUUID()).stream()
                .filter(i -> i.getDefinition().id().toString().equals(definitionId))
                .findFirst().orElse(null);
        if (q == null) {
            source.sendFailure(Component.literal("玩家没有该任务: " + definitionId));
            return 0;
        }
        boolean newStar = !q.isStarred();
        QuestSystem.getInstance().setStarred(q, newStar);
        source.sendSuccess(() -> Component.literal("星标: " + (newStar ? "§a开启" : "§c关闭")
                + " " + q.getDefinition().title().getString()), true);
        return 1;
    }

    // ========== 工具 ==========

    private static QuestInstance findActive(ServerPlayer player, String definitionId) {
        return QuestSystem.getInstance().getPlayerQuests(player.getUUID()).stream()
                .filter(i -> i.isActive() && i.getDefinition().id().toString().equals(definitionId))
                .findFirst().orElse(null);
    }

    private static String stateCn(QuestInstance quest) {
        return switch (quest.getState()) {
            case COMPLETED -> "§a完成";
            case FAILED -> "§c失败";
            case REMOVED -> "§7已消除";
            default -> "§f进行中";
        };
    }

    private static String starMark(QuestInstance quest) {
        return quest.isStarred() ? " §e★" : "";
    }
}
