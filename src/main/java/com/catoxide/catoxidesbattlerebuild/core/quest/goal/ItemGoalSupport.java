package com.catoxide.catoxidesbattlerebuild.core.quest.goal;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * 物品目标的公共查询/扣除逻辑（主背包 + 副手；不含盔甲槽）。
 * <p>只做纯查询与显式扣除：{@link #count} 不改变玩家状态，扣除只由任务完成回调触发。
 */
public final class ItemGoalSupport {

    private static final String TAG = "QuestItemGoal";

    private ItemGoalSupport() {
    }

    /** 解析物品 id；无命名空间默认 {@code minecraft:}。语法非法返回 null。 */
    public static ResourceLocation parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        return ResourceLocation.tryParse(s.indexOf(':') >= 0 ? s : "minecraft:" + s);
    }

    /**
     * 解析成已注册的物品；未注册 / 空气返回 null。
     * <p>物品 id 写错时不能静默当成「玩家没有该物品」——那是永远完不成的任务，
     * 调用方（工厂）应据此抛错，让任务定义在加载阶段被拒绝。
     */
    public static Item resolve(String raw) {
        ResourceLocation id = parseId(raw);
        if (id == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return (item == null || item == Items.AIR) ? null : item;
    }

    /** 玩家当前持有的该物品总数（主背包 36 格 + 副手）。 */
    public static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (matches(stack, item)) {
                total += stack.getCount();
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (matches(stack, item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 扣除指定数量，返回实际扣除量（不足时扣光）。
     * <p>先主背包后副手；扣完广播容器变化，客户端立即看到物品消失。
     */
    public static int consume(ServerPlayer player, Item item, int amount) {
        int remaining = drain(player.getInventory().items, item, amount);
        remaining = drain(player.getInventory().offhand, item, remaining);
        if (remaining < amount) {
            player.inventoryMenu.broadcastChanges();
        }
        if (remaining > 0) {
            LogManager.serverWarn(TAG, "Item consume short: wanted %d, missing %d (item=%s, player=%s)",
                    amount, remaining, BuiltInRegistries.ITEM.getKey(item), player.getUUID());
        }
        return amount - remaining;
    }

    private static int drain(List<ItemStack> slots, Item item, int amount) {
        int remaining = amount;
        for (ItemStack stack : slots) {
            if (remaining <= 0) {
                break;
            }
            if (!matches(stack, item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        return remaining;
    }

    private static boolean matches(ItemStack stack, Item item) {
        return !stack.isEmpty() && stack.is(item);
    }
}
