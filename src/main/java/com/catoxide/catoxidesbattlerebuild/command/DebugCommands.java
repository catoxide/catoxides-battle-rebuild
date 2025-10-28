// DebugCommands.java - 简化版本
package com.catoxide.catoxidesbattlerebuild.command;

import com.catoxide.catoxidesbattlerebuild.mob.HitboxPart;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DebugCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 只保留 hitboxdebug 相关命令
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
        );
    }
}