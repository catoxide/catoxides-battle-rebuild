package example.contentpack.containeroverhaul.ui;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 调试入口：{@code /containeroverhaul sidepanel upgrades <0..2>}。
 *
 * <p>直接改玩家面板存档里的升级槽（填/清箱子），而不是伪造一个「已解锁数」——
 * 这样客户端看到的仍然是真实的 {@code unlockedCount} 计算结果，走的也是菜单的常规同步，
 * 不会出现「客户端以为解锁、服务端拒绝」的两套状态。
 *
 * <p>缩到会锁住在用格时拒绝执行：那些格一旦锁上，{@code mayPickup} 就不让取了。
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
                                        .executes(SidePanelCommands::setUpgrades)))));
    }

    private static int setUpgrades(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int count = IntegerArgumentType.getInteger(context, "count");
        SimpleContainer container = PanelStorage.containerFor(player);

        int unlocked = Math.min(SidePanel.BASE_UNLOCKED + count * SidePanel.UNLOCK_PER_UPGRADE,
                SidePanel.STORAGE_COUNT);
        for (int i = unlocked; i < SidePanel.STORAGE_COUNT; i++) {
            if (!container.getItem(i).isEmpty()) {
                context.getSource().sendFailure(Component.literal(
                        "side panel: storage slot " + i + " is occupied; empty it before locking"));
                return 0;
            }
        }

        for (int i = 0; i < SidePanel.UPGRADE_COLS; i++) {
            container.setItem(SidePanel.UPGRADE_START + i, i < count ? new ItemStack(Items.CHEST) : ItemStack.EMPTY);
        }
        context.getSource().sendSuccess(() -> Component.literal(
                "side panel: " + unlocked + "/" + SidePanel.STORAGE_COUNT + " storage slots unlocked"), true);
        return count;
    }
}
