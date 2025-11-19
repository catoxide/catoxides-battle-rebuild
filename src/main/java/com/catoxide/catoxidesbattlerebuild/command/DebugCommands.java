// DebugCommands.java - 增强版本
package com.catoxide.catoxidesbattlerebuild.command;

import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.HitboxPart;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.debug.EnhancedDebugManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class DebugCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 碰撞箱调试命令
        dispatcher.register(Commands.literal("hitboxdebug")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("info")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            Level level = source.getLevel();

                            // 统计所有实体
                            int zombieCount = level.getEntitiesOfClass(ModularZombie.class,
                                    new AABB(source.getPosition(), source.getPosition()).inflate(50)).size();

                            int hitboxCount = level.getEntitiesOfClass(HitboxPart.class,
                                    new AABB(source.getPosition(), source.getPosition()).inflate(50)).size();

                            source.sendSuccess(() -> Component.literal("实体统计 - Zombie: " + zombieCount + ", Hitbox: " + hitboxCount), true);

                            // 详细统计每个僵尸的碰撞箱
                            List<ModularZombie> zombies = level.getEntitiesOfClass(ModularZombie.class,
                                    new AABB(source.getPosition(), source.getPosition()).inflate(50));

                            for (ModularZombie zombie : zombies) {
                                if (zombie.getBodyPartManager() != null) {
                                    String debugInfo = zombie.getBodyPartManager().getDebugInfo();
                                    source.sendSuccess(() -> Component.literal(debugInfo), false);
                                }
                            }

                            return 1;
                        })
                )
                .then(Commands.literal("clear")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            Level level = source.getLevel();

                            // 清除所有HitboxPart实体
                            List<HitboxPart> hitboxes = level.getEntitiesOfClass(HitboxPart.class,
                                    new AABB(source.getPosition(), source.getPosition()).inflate(50));

                            int removedCount = 0;
                            for (HitboxPart hitbox : hitboxes) {
                                hitbox.remove(Entity.RemovalReason.DISCARDED);
                                removedCount++;
                            }
                            return 1;
                        })
                )
        );

        // === 新增：变换管道调试命令 ===
        dispatcher.register(Commands.literal("transformdebug")
                .requires(source -> source.hasPermission(2))

                // 基础状态控制
                .then(Commands.literal("status")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            String status = EnhancedDebugManager.getStatus();
                            source.sendSuccess(() -> Component.literal("🔧 变换管道状态: " + status), false);
                            return 1;
                        })
                )
                .then(Commands.literal("detailed")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            EnhancedDebugManager.printDetailedStatus();
                            source.sendSuccess(() -> Component.literal("详细状态已输出到控制台"), false);
                            return 1;
                        })
                )

                // 跳过变换模块
                .then(Commands.literal("skip")
                        .then(Commands.literal("position")
                                .executes(context -> {
                                    EnhancedDebugManager.skipPosition();
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已跳过位置变换"), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("rotation")
                                .executes(context -> {
                                    EnhancedDebugManager.skipRotation();
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已跳过旋转变换"), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("pivot")
                                .executes(context -> {
                                    EnhancedDebugManager.skipPivot();
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已跳过轴心变换"), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("scale")
                                .executes(context -> {
                                    EnhancedDebugManager.skipScale();
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已跳过缩放变换"), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("all")
                                .executes(context -> {
                                    EnhancedDebugManager.skipAll();
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已跳过所有变换"), false);
                                    return 1;
                                })
                        )
                )

                // 启用变换模块
                .then(Commands.literal("enable")
                        .then(Commands.literal("all")
                                .executes(context -> {
                                    EnhancedDebugManager.enableAll();
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已启用所有变换"), false);
                                    return 1;
                                })
                        )
                        .then(Commands.argument("module", StringArgumentType.word())
                                .executes(context -> {
                                    String module = StringArgumentType.getString(context, "module");
                                    EnhancedDebugManager.enableModule(module);
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已启用模块: " + module), false);
                                    return 1;
                                })
                        )
                )

                // 调试预设
                .then(Commands.literal("preset")
                        .then(Commands.literal("position")
                                .executes(context -> {
                                    EnhancedDebugManager.debugPositionIssues();
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已启用位置问题调试预设"), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("rotation")
                                .executes(context -> {
                                    EnhancedDebugManager.debugRotationIssues();
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已启用旋转问题调试预设"), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("pivot")
                                .executes(context -> {
                                    EnhancedDebugManager.debugPivotIssues();
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已启用轴心问题调试预设"), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("scale")
                                .executes(context -> {
                                    EnhancedDebugManager.debugScaleIssues();
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已启用缩放问题调试预设"), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("basic")
                                .executes(context -> {
                                    EnhancedDebugManager.debugBasicPosition();
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已启用基础位置调试预设"), false);
                                    return 1;
                                })
                        )
                )

                // 测试功能
                .then(Commands.literal("test")
                        .executes(context -> {
                            EnhancedDebugManager.testPipeline();
                            CommandSourceStack source = context.getSource();
                            source.sendSuccess(() -> Component.literal("管道测试已完成，请查看控制台输出"), false);
                            return 1;
                        })
                )

                // 重置
                .then(Commands.literal("reset")
                        .executes(context -> {
                            EnhancedDebugManager.reset();
                            CommandSourceStack source = context.getSource();
                            source.sendSuccess(() -> Component.literal("已重置所有变换设置"), false);
                            return 1;
                        })
                )

                // 模块切换
                .then(Commands.literal("toggle")
                        .then(Commands.argument("module", StringArgumentType.word())
                                .executes(context -> {
                                    String module = StringArgumentType.getString(context, "module");
                                    EnhancedDebugManager.toggleModule(module);
                                    CommandSourceStack source = context.getSource();
                                    source.sendSuccess(() -> Component.literal("已切换模块: " + module), false);
                                    return 1;
                                })
                        )
                )
        );

        // === 新增：快捷调试命令 ===
        dispatcher.register(Commands.literal("debug")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("transform")
                        .executes(context -> {
                            String status = EnhancedDebugManager.getStatus();
                            CommandSourceStack source = context.getSource();
                            source.sendSuccess(() -> Component.literal("🔧 当前变换状态: " + status), false);
                            return 1;
                        })
                )
                .then(Commands.literal("entities")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            Level level = source.getLevel();

                            int zombieCount = level.getEntitiesOfClass(ModularZombie.class,
                                    new AABB(source.getPosition(), source.getPosition()).inflate(50)).size();
                            int hitboxCount = level.getEntitiesOfClass(HitboxPart.class,
                                    new AABB(source.getPosition(), source.getPosition()).inflate(50)).size();

                            source.sendSuccess(() -> Component.literal("🎯 实体统计: " + zombieCount + " 僵尸, " + hitboxCount + " 碰撞箱"), false);
                            return 1;
                        })
                )
        );
    }
}