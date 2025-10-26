// DebugCommands.java
package com.catoxide.catoxidesbattlerebuild.command;

import com.catoxide.catoxidesbattlerebuild.client.renderer.CustomHitboxRenderer;
import com.catoxide.catoxidesbattlerebuild.mob.HitboxPart;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DebugCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("debugzombie")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawnhitboxes")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            Level level = source.getLevel();

                            List<ModularZombie> zombies = level.getEntitiesOfClass(ModularZombie.class,
                                    new AABB(source.getPosition(), source.getPosition()).inflate(50));

                            source.sendSuccess(() -> Component.literal("找到 " + zombies.size() + " 个 ModularZombie"), true);

                            for (ModularZombie zombie : zombies) {
                                try {
                                    if (zombie.getBodyPartManager() != null) {
                                        zombie.getBodyPartManager().spawnHitboxEntities();
                                        source.sendSuccess(() -> Component.literal("为僵尸 " + zombie.getUUID() + " 生成碰撞箱"), true);
                                    }
                                } catch (Exception e) {
                                    source.sendFailure(Component.literal("生成碰撞箱失败: " + e.getMessage()));
                                }
                            }

                            return 1;
                        })
                )
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
                .then(Commands.literal("cleanup")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            Level level = source.getLevel();

                            // 清理所有没有父实体的碰撞箱
                            List<HitboxPart> hitboxes = level.getEntitiesOfClass(HitboxPart.class,
                                    new AABB(source.getPosition(), source.getPosition()).inflate(50));

                            AtomicInteger removedCount = new AtomicInteger(0);
                            for (HitboxPart hitbox : hitboxes) {
                                if (hitbox.getParent() == null || !hitbox.getParent().isAlive()) {
                                    hitbox.discard();
                                    removedCount.incrementAndGet();
                                }
                            }

                            source.sendSuccess(() -> Component.literal("清理了 " + removedCount.get() + " 个孤儿碰撞箱"), true);
                            return 1;
                        })

        ));
        dispatcher.register(Commands.literal("hitbox")
                .requires(source -> source.hasPermission(2)) // 需要权限等级2
                .then(Commands.literal("toggle")
                        .executes(context -> {
                            CustomHitboxRenderer.toggleRendering();
                            context.getSource().sendSuccess(
                                    () -> Component.literal("自定义碰撞箱渲染: " +
                                            (CustomHitboxRenderer.renderCustomHitboxes ? "开启" : "关闭")),
                                    true
                            );
                            return 1;
                        })
                )
                .then(Commands.literal("set")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> {
                                    boolean enabled = BoolArgumentType.getBool(context, "enabled");
                                    CustomHitboxRenderer.setRendering(enabled);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("自定义碰撞箱渲染: " + (enabled ? "开启" : "关闭")),
                                            true
                                    );
                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("status")
                        .executes(context -> {
                            context.getSource().sendSuccess(
                                    () -> Component.literal("自定义碰撞箱渲染状态: " +
                                            (CustomHitboxRenderer.renderCustomHitboxes ? "开启" : "关闭")),
                                    true
                            );
                            return 1;
                        })
                )
                .then(Commands.literal("hitboxdebug")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            Level level = source.getLevel();

                            List<HitboxPart> hitboxes = level.getEntitiesOfClass(HitboxPart.class,
                                    new AABB(source.getPosition(), source.getPosition()).inflate(50));

                            source.sendSuccess(() -> Component.literal("找到 " + hitboxes.size() + " 个 HitboxPart"), true);

                            for (HitboxPart hitbox : hitboxes) {
                                String parentInfo = hitbox.getParent() != null ?
                                        hitbox.getParent().getName().getString() + " (存活: " + hitbox.getParent().isAlive() + ")" :
                                        "null";

                                source.sendSuccess(() -> Component.literal(
                                        "Hitbox: " + hitbox.getBodyPart() +
                                                " | 位置: " + hitbox.position() +
                                                " | 父实体: " + parentInfo +
                                                " | 碰撞箱: " + hitbox.getBoundingBox()
                                ), false);
                            }

                            return 1;
                        })
                )
        );
    }
}