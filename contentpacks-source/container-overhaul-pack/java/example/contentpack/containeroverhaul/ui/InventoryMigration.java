package example.contentpack.containeroverhaul.ui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 旧侧面板储物 → 玩家物品栏的迁移，以及登录 / 重生时的接线。
 *
 * <p>旧 {@link PanelStorage} 的 0–44 是独立储物格：0–35 迁到物品栏 9–44（刚好接在快捷栏后面），
 * 36–44 没有对应格，留在旧存档等 {@code /containeroverhaul recover}。迁移只写「已解锁且为空」的
 * 目标格 —— 宁可少迁，绝不覆盖玩家现有物品；迁不动的留在原处，下次登录再试。
 */
public final class InventoryMigration {

    private InventoryMigration() {}

    public static void install() {
        NeoForge.EVENT_BUS.addListener(InventoryMigration::onLogin);
        NeoForge.EVENT_BUS.addListener(InventoryMigration::onClone);
    }

    /** 旧储物格 → 新物品栏下标；超出物品格范围返回 -1（只能走 {@link #recover}）。 */
    public static int migratedSlot(int legacySlot) {
        if (legacySlot < 0 || legacySlot >= SidePanel.MAX_ITEM_SLOTS) {
            return -1;
        }
        int target = legacySlot + SidePanel.ROW_SLOTS;
        return target < SidePanel.MAX_ITEM_SLOTS ? target : -1;
    }

    /** 按固定映射迁移；返回迁动的格数。 */
    public static int migrate(ServerPlayer player) {
        SimpleContainer legacy = PanelStorage.containerFor(player);
        Inventory inventory = player.getInventory();
        int unlocked = SidePanel.unlockedCount(Upgrades.of(player));
        int moved = 0;
        for (int slot = 0; slot < SidePanel.MAX_ITEM_SLOTS; slot++) {
            ItemStack stack = legacy.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int target = migratedSlot(slot);
            if (target < 0 || target >= unlocked || !inventory.getItem(target).isEmpty()) {
                continue;
            }
            inventory.setItem(target, stack.copy());
            legacy.setItem(slot, ItemStack.EMPTY);
            moved++;
        }
        return moved;
    }

    /** 把旧存档里剩下的东西塞进任意解锁空位；返回搬动的格数。 */
    public static int recover(ServerPlayer player) {
        SimpleContainer legacy = PanelStorage.containerFor(player);
        Inventory inventory = player.getInventory();
        int unlocked = SidePanel.unlockedCount(Upgrades.of(player));
        int moved = 0;
        for (int slot = 0; slot < SidePanel.MAX_ITEM_SLOTS; slot++) {
            ItemStack stack = legacy.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int target = firstEmpty(inventory, unlocked);
            if (target < 0) {
                break;
            }
            inventory.setItem(target, stack.copy());
            legacy.setItem(slot, ItemStack.EMPTY);
            moved++;
        }
        return moved;
    }

    private static int firstEmpty(Inventory inventory, int unlocked) {
        for (int slot = 0; slot < unlocked; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Upgrades.load(player);
        migrate(player);
    }

    private static void onClone(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer target)) {
            return;
        }
        if (!(event.getOriginal() instanceof ServerPlayer original)) {
            return;
        }
        Upgrades.copy(original, target);
    }
}
