package example.contentpack.containeroverhaul.ui;

import com.catoxide.catoxidesbattlerebuild.core.inventory.IPlayerInventoryPolicy;
import com.catoxide.catoxidesbattlerebuild.core.inventory.PlayerInventoryPolicies;
import net.minecraft.world.entity.player.Player;

/**
 * 解锁策略：升级槽数量决定可用的物品格数。
 *
 * <p>服务端以 {@link Upgrades} 为准；客户端没有那份常驻容器，改看当前菜单挂载时拿到的升级容器
 * （菜单槽位会同步内容）。屏幕没挂载（创造模式等）时按 45 放行 —— 服务端仍是权威，
 * 放行只影响显示，不会真的写进锁住的格。
 */
public final class InventoryCapacityPolicy implements IPlayerInventoryPolicy {

    private static final InventoryCapacityPolicy INSTANCE = new InventoryCapacityPolicy();

    private InventoryCapacityPolicy() {}

    public static void register() {
        PlayerInventoryPolicies.register(INSTANCE);
    }

    @Override
    public int unlockedItemSlots(Player player) {
        if (player == null) {
            return SidePanel.MAX_ITEM_SLOTS;
        }
        if (player.level().isClientSide) {
            SidePanelMounts.View view = SidePanelMounts.view(player.containerMenu);
            return view == null ? SidePanel.MAX_ITEM_SLOTS : SidePanel.unlockedCount(view.upgrades());
        }
        return SidePanel.unlockedCount(Upgrades.of(player));
    }
}
