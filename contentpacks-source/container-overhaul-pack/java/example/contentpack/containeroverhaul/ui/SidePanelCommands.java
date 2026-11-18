package example.contentpack.containeroverhaul.ui;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 调试入口：{@code /containeroverhaul sidepanel upgrades <0..4>} 与
 * {@code /containeroverhaul sidepanel recover}。
 *
 * <p>直接改玩家的常驻升级容器（填/清箱子），而不是伪造一个「已解锁数」——
 * 客户端看到的仍然是真实的 {@code unlockedCount} 计算结果，走的也是菜单的常规同步，
 * 不会出现「客户端以为解锁、服务端拒绝」的两套状态。
 *
 * <p>缩到会锁住在用格时拒绝执行：那些格一旦锁上，{@code mayPickup} 就不让取了。
 * {@code recover} 把旧侧面板存档里还没迁走的东西塞进解锁空位。
 */
public final class SidePanelCommands {

    private SidePanelCommands() {}

    public static void install() {
        NeoForge.EVENT_BUS.addListener(SidePanelCommands::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("containeroverhaul")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("sidepanel")
                        .then(Commands.literal("upgrades")
                                .then(Commands.argument("count", IntegerArgumentType.integer(0, SidePanel.UPGRADE_COLS))
                                        .executes(SidePanelCommands::setUpgrades)))
                        .then(Commands.literal("recover").executes(SidePanelCommands::recover))));
    }

    private static int setUpgrades(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int count = IntegerArgumentType.getInteger(context, "count");
        int unlocked = Math.min(SidePanel.BASE_UNLOCKED + count * SidePanel.UNLOCK_PER_UPGRADE,
                SidePanel.MAX_ITEM_SLOTS);

        Inventory inventory = player.getInventory();
        for (int slot = unlocked; slot < SidePanel.MAX_ITEM_SLOTS; slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                context.getSource().sendFailure(Component.literal(
                        "inventory slot " + slot + " is occupied; empty it before locking"));
                return 0;
            }
        }

        SimpleContainer upgrades = Upgrades.of(player);
        for (int i = 0; i < SidePanel.UPGRADE_COLS; i++) {
            upgrades.setItem(i, i < count ? new ItemStack(Items.CHEST) : ItemStack.EMPTY);
        }
        context.getSource().sendSuccess(() -> Component.literal(
                "item slots: " + unlocked + "/" + SidePanel.MAX_ITEM_SLOTS + " unlocked"), true);
        return count;
    }

    private static int recover(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int moved = InventoryMigration.recover(player);
        context.getSource().sendSuccess(() -> Component.literal(
                "recovered " + moved + " stack(s) from the old side panel storage"), true);
        return moved;
    }
}
