package com.catoxide.catoxidesbattlerebuild.core.quest.event;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 任务事件桥（基座基础设施）。
 * <p>把 NeoForge 通用游戏事件转换为任务专用事件，广播到 EVENT_BUS——
 * 内容包目标组件（IQuestGoal）直接订阅这些事件即可实现击杀/破块/放置/合成等任务，
 * 无需自己监听 NeoForge 事件 + 过滤玩家。
 */
@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID)
public abstract class QuestEventBridge {

    // ========== 任务事件（内容包 goal 订阅） ==========

    /** 玩家击杀生物 */
    public static class MobKilledEvent extends Event {
        public final ServerPlayer player;
        public final LivingEntity target;

        public MobKilledEvent(ServerPlayer player, LivingEntity target) {
            this.player = player;
            this.target = target;
        }
    }

    /** 玩家破坏方块 */
    public static class BlockBrokenEvent extends Event {
        public final ServerPlayer player;
        public final BlockPos pos;
        public final BlockState state;

        public BlockBrokenEvent(ServerPlayer player, BlockPos pos, BlockState state) {
            this.player = player;
            this.pos = pos;
            this.state = state;
        }
    }

    /** 玩家放置方块 */
    public static class BlockPlacedEvent extends Event {
        public final ServerPlayer player;
        public final BlockPos pos;
        public final BlockState state;

        public BlockPlacedEvent(ServerPlayer player, BlockPos pos, BlockState state) {
            this.player = player;
            this.pos = pos;
            this.state = state;
        }
    }

    /** 玩家合成物品 */
    public static class ItemCraftedEvent extends Event {
        public final ServerPlayer player;
        public final ItemStack result;

        public ItemCraftedEvent(ServerPlayer player, ItemStack result) {
            this.player = player;
            this.result = result;
        }
    }

    // ========== NeoForge 监听 → 任务事件转换 ==========

    @SubscribeEvent
    public static void onMobKilled(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer player) {
            NeoForge.EVENT_BUS.post(new MobKilledEvent(player, event.getEntity()));
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            NeoForge.EVENT_BUS.post(new BlockBrokenEvent(player, event.getPos(), event.getState()));
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NeoForge.EVENT_BUS.post(new BlockPlacedEvent(player, event.getPos(), event.getPlacedBlock()));
        }
    }

    @SubscribeEvent
    public static void onItemCraft(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NeoForge.EVENT_BUS.post(new ItemCraftedEvent(player, event.getCrafting()));
        }
    }
}
