package example.contentpack.tech;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 节点解锁成本。
 * <p>{@link #canPay} 与 {@link #pay} 分离：服务端先整体校验全部成本，再统一扣除，
 * 避免「扣了前一项、后一项不够」的半扣状态。
 */
public sealed interface TechCost
        permits TechCost.ItemCost, TechCost.XpCost, TechCost.TechPointCost {

    int amount();

    boolean canPay(Player player);

    /** 只在服务端线程、且 {@link #canPay} 已通过时调用。 */
    void pay(ServerPlayer player);

    /** 纯文本摘要（任务描述/调试用）。 */
    String describePlain();

    record ItemCost(ResourceLocation itemId, int amount) implements TechCost {

        @Override
        public boolean canPay(Player player) {
            Item item = TechItems.resolve(itemId);
            return item != null && count(player, item) >= amount;
        }

        @Override
        public void pay(ServerPlayer player) {
            Item item = TechItems.resolve(itemId);
            if (item != null) {
                consume(player, item, amount);
            }
        }

        @Override
        public String describePlain() {
            return "物品 " + itemId + " ×" + amount;
        }
    }

    record XpCost(int amount) implements TechCost {

        @Override
        public boolean canPay(Player player) {
            return player.experienceLevel >= amount;
        }

        @Override
        public void pay(ServerPlayer player) {
            player.giveExperienceLevels(-amount);
        }

        @Override
        public String describePlain() {
            return "经验等级 ×" + amount;
        }
    }

    /** 科技点：玩家虚拟余额（存档级），不占物品栏。 */
    record TechPointCost(int amount) implements TechCost {

        @Override
        public boolean canPay(Player player) {
            return TechService.points(player) >= amount;
        }

        @Override
        public void pay(ServerPlayer player) {
            TechService.spendPoints(player, amount);
        }

        @Override
        public String describePlain() {
            return "科技点 ×" + amount;
        }
    }

    /** 主背包 + 副手的数量（不含盔甲槽，避免把穿戴物品当货币扣掉）。 */
    static int count(Player player, Item item) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (ItemStack stack : inventory.items) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        for (ItemStack stack : inventory.offhand) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    static void consume(Player player, Item item, int amount) {
        Inventory inventory = player.getInventory();
        int remaining = amount;
        for (ItemStack stack : inventory.items) {
            if (remaining <= 0) {
                return;
            }
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        for (ItemStack stack : inventory.offhand) {
            if (remaining <= 0) {
                return;
            }
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
    }
}
