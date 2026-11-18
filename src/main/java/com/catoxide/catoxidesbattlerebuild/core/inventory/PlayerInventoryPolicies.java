package com.catoxide.catoxidesbattlerebuild.core.inventory;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 玩家物品栏解锁策略注册表（同 {@code ContainerPolicies} 的形状：core 只登记与遍历）。
 *
 * <p>多个策略取最小值 —— 解锁是「可用格数」的上界，任何一方认为锁着就不能用。
 * 注册表为空时返回 {@link PlayerInventoryLayout#ITEM_SLOTS}，等于完全不动原版行为（R3）。
 */
public final class PlayerInventoryPolicies {

    private static final String TAG = "PlayerInventoryPolicies";
    private static final List<IPlayerInventoryPolicy> POLICIES = new CopyOnWriteArrayList<>();

    private PlayerInventoryPolicies() {}

    public static void register(IPlayerInventoryPolicy policy) {
        POLICIES.add(policy);
        LogManager.serverInfo(TAG, "PlayerInventoryPolicy registered: {}", policy.getClass().getName());
    }

    public static boolean isEmpty() {
        return POLICIES.isEmpty();
    }

    public static int unlockedItemSlots(Player player) {
        int unlocked = PlayerInventoryLayout.ITEM_SLOTS;
        if (player == null) {
            return unlocked;
        }
        for (IPlayerInventoryPolicy policy : POLICIES) {
            int value;
            try {
                value = policy.unlockedItemSlots(player);
            } catch (RuntimeException error) {
                LogManager.serverError(TAG, "policy %s failed: %s", policy.getClass().getName(), error);
                continue;
            }
            unlocked = Math.min(unlocked, Math.max(0, Math.min(value, PlayerInventoryLayout.ITEM_SLOTS)));
        }
        return unlocked;
    }
}
