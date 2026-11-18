package example.contentpack.containeroverhaul.ui;

import com.catoxide.catoxidesbattlerebuild.core.inventory.PlayerInventoryLayout;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 悬浮物品栏与升级板的几何 / 容量规则。
 *
 * <p>物品栏本身仍然是玩家真实的 {@code Inventory}（0–8 快捷栏、9–44 背包四行）；这里只决定
 * 「这一格画在哪一行」和「现在解锁了哪些行」。快捷栏钉在板底，背包行按解锁数量往上长：
 * 9 格 = 只有快捷栏，18 = 快捷栏 + 第 1 行，……，45 = 快捷栏 + 4 行。
 *
 * <p>底板不读整板贴图：运行时按 {@code panel_skin.png} 九宫格现拼、用原版 slot 精灵补格底
 * （见 {@code PanelPainter}），宽度固定 170；{@code board_1..board_5.png} 只作为校验
 * 「现拼结果 = 烘出来的样子」的参照物。行数 = 1（快捷栏）+ 已解锁背包行；背包行之间紧贴，
 * 快捷栏单独往下让开 {@link #HOTBAR_GAP} —— 原版快捷栏（y=142）与主栏底行（y=120）之间正是 22px。
 * 升级板仍是一块 4 格浮板，挂在快捷栏下方。
 *
 * <p>槽位不写菜单序号：背包第 4 行与升级槽都经 {@code SlotBookings} 登记，序号由登记处分配。
 * 旧侧面板的 45 格储物已取消；{@link PanelStorage} 只作为迁移来源保留旧布局
 * （0–44 储物、45–48 升级），所以 {@link #UPGRADE_START} / {@link #COUNT} 仍指向旧容器。
 */
public final class SidePanel {

    public static final String NAMESPACE = "container-overhaul-pack";
    public static final ResourceLocation BACKPACK_TYPE =
            ResourceLocation.fromNamespaceAndPath(NAMESPACE, "side_panel/backpack");
    public static final ResourceLocation UPGRADE_TYPE =
            ResourceLocation.fromNamespaceAndPath(NAMESPACE, "side_panel/upgrade");

    public static final int SLOT_PITCH = 18;
    public static final int ROW_SLOTS = 9;
    public static final int BACKPACK_ROWS = 4;
    public static final int MAX_ITEM_SLOTS = 45;
    /** 第 4 行新增格的容器下标（原版 36 格之后的 36–44）。 */
    public static final int EXTRA_ROW_START = 36;
    public static final int EXTRA_ROW_SLOTS = 9;

    /** 一块浮板：9 列 + 左右各 4px 边；背包行距 = 原版 18px，板子按行数纵向延长。 */
    public static final int ROW_INSET = 4;
    public static final int ROW_WIDTH = ROW_INSET * 2 + ROW_SLOTS * SLOT_PITCH;
    public static final int ROW_PITCH = SLOT_PITCH;
    /**
     * 快捷栏与它上面那行背包之间的额外缝：原版物品栏快捷栏在 y=142、主栏底行在 y=120，
     * 22 = 18 + 4。只有快捷栏这一行让开，背包行之间仍按 {@link #ROW_PITCH} 紧贴。
     */
    public static final int HOTBAR_GAP = 4;

    /** 浮板离容器面板右边留 4px；板子的 x 由面板宽推出（见 {@link #boardX(int)}）。 */
    public static final int BOARD_GAP = 4;
    /**
     * 挂槽位时的初值宽度：菜单构造时屏还不存在（屏在 {@code MenuScreens.create} 里才建），
     * 拿不到真实面板宽；客户端每帧 {@code place()} 会按真实宽度重摆。
     */
    public static final int DEFAULT_GUI_WIDTH = 176;
    /**
     * 快捷栏行在标准屏（物品栏 / 176×166 各屏）里的顶边（相对 guiTop）；背包行往上长，
     * 板顶见 {@link #boardY(int, int)}。
     *
     * <p>各屏快捷栏位置不同（漏斗 109、1 行箱子 107、信标 195、6 行箱子 196……），
     * 板底钉的是<b>这一屏自己的快捷栏</b>：菜单侧从原版 0–8 槽位的 y 读出来（{@code View#hotbarY}），
     * 这个常量只是没有菜单信息时的兜底。菜单构造时屏还不存在，补挂槽位先用它，客户端每帧重摆。
     */
    public static final int DEFAULT_HOTBAR_Y = 142;

    /** 升级板：4 格一行，独立浮板，挂在快捷栏下方，位置不随背包行数变。 */
    public static final int UPGRADE_COLS = 4;
    public static final int UPGRADE_INSET = 4;
    public static final int UPGRADE_WIDTH = UPGRADE_INSET * 2 + UPGRADE_COLS * SLOT_PITCH;
    public static final int UPGRADE_HEIGHT = UPGRADE_INSET * 2 + SLOT_PITCH;
    public static final int UPGRADE_GAP = 2;

    public static final int BASE_UNLOCKED = 9;
    public static final int UNLOCK_PER_UPGRADE = 9;

    /**
     * 截底线（相对 guiTop）由各屏自己的玩家主栏位置推：见 {@code ContainerCut}。
     *
     * <p>原版把玩家主栏（9–35）固定放在容器内容下方，所以「主栏顶边 - 1」就是这一屏
     * 「容器内容已画完、玩家物品栏块开始」的地方。不按屏硬编码，原版各屏与后续新屏都适用。
     */
    public static final int VANILLA_MAIN_ROWS = 3;

    /** 原版玩家主栏的容器下标（快捷栏之后紧接的三行），原版各屏都按这个下标放主栏。 */
    public static boolean isVanillaMainSlot(int containerSlot) {
        return containerSlot >= PlayerInventoryLayout.HOTBAR_SLOTS
                && containerSlot < PlayerInventoryLayout.HOTBAR_SLOTS + ROW_SLOTS * VANILLA_MAIN_ROWS;
    }

    /** 旧 {@link PanelStorage} 容器的布局（仅迁移用）：0–44 储物、45–48 升级。 */
    public static final int UPGRADE_START = MAX_ITEM_SLOTS;
    public static final int COUNT = MAX_ITEM_SLOTS + UPGRADE_COLS;

    private SidePanel() {}

    /** 4 格升级容器的下标 0–3。 */
    public static int unlockedCount(Container upgrades) {
        int unlocked = BASE_UNLOCKED;
        for (int i = 0; i < UPGRADE_COLS; i++) {
            if (!upgrades.getItem(i).isEmpty()) {
                unlocked += UNLOCK_PER_UPGRADE;
            }
        }
        return Math.min(unlocked, MAX_ITEM_SLOTS);
    }

    public static int unlockedCountWithout(Container upgrades, int excludedUpgrade) {
        int unlocked = BASE_UNLOCKED;
        for (int i = 0; i < UPGRADE_COLS; i++) {
            if (i != excludedUpgrade && !upgrades.getItem(i).isEmpty()) {
                unlocked += UNLOCK_PER_UPGRADE;
            }
        }
        return Math.min(unlocked, MAX_ITEM_SLOTS);
    }

    /** 已解锁的背包行数（0–4）。 */
    public static int backpackRows(Container upgrades) {
        return unlockedCount(upgrades) / ROW_SLOTS - 1;
    }

    /** 底板行数 = 快捷栏 1 行 + 已解锁背包行（1–5）。 */
    public static int boardRows(Container upgrades) {
        return backpackRows(upgrades) + 1;
    }

    public static boolean canHold(int containerSlot, Container upgrades) {
        return containerSlot >= 0 && containerSlot < unlockedCount(upgrades);
    }

    public static ResourceLocation backpackKey(int containerSlot) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, "side_panel/backpack/" + containerSlot);
    }

    public static ResourceLocation upgradeKey(int col) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, "side_panel/upgrade/" + col);
    }

    /** 整块板的左边缘（相对 guiLeft）：贴在容器面板右边外面。 */
    public static int boardX(int guiWidth) {
        return guiWidth + BOARD_GAP;
    }

    /** 整块板的顶边（相对 guiTop）：快捷栏那一行之上留 {@link #ROW_INSET} 边，行数越多越往上。 */
    public static int boardY(int hotbarY, int rows) {
        return hotbarY - (rows - 1) * ROW_PITCH - (rows > 1 ? HOTBAR_GAP : 0) - ROW_INSET;
    }

    /** 整块板的高度：行数 × 行距 + 快捷栏缝 + 上下各 {@link #ROW_INSET} 边。 */
    public static int boardHeight(int rows) {
        return rows * ROW_PITCH + (rows > 1 ? HOTBAR_GAP : 0) + ROW_INSET * 2;
    }

    /** 快捷栏 / 背包格在板内的横向位置（列与容器下标无关，统一 9 列）。 */
    public static int slotX(int guiWidth, int containerSlot) {
        return boardX(guiWidth) + ROW_INSET + 1 + (containerSlot % ROW_SLOTS) * SLOT_PITCH;
    }

    /** 板内行号：0 = 快捷栏，1–4 = 背包行（往上）。 */
    public static int slotRow(int containerSlot) {
        if (containerSlot < ROW_SLOTS) {
            return 0;
        }
        return (containerSlot - ROW_SLOTS) / ROW_SLOTS + 1;
    }

    /** 纵向位置：快捷栏钉在 {@code hotbarY}，背包行按行号往上排（行距 18，快捷栏再让开 {@link #HOTBAR_GAP}）。 */
    public static int slotY(int hotbarY, int containerSlot) {
        int row = slotRow(containerSlot);
        return hotbarY - row * ROW_PITCH - (row > 0 ? HOTBAR_GAP : 0) + 1;
    }

    public static int upgradePanelY(int hotbarY) {
        return hotbarY + ROW_PITCH + ROW_INSET + UPGRADE_GAP;
    }

    public static int upgradeSlotX(int guiWidth, int col) {
        return boardX(guiWidth) + UPGRADE_INSET + 1 + col * SLOT_PITCH;
    }

    public static int upgradeSlotY(int hotbarY) {
        return upgradePanelY(hotbarY) + UPGRADE_INSET + 1;
    }

    /**
     * 升级槽：容器是 4 格升级容器（不是物品栏）。
     *
     * <p>拔掉升级会把后面的背包行重新锁上；那些格里还有东西时不许拔 —— 锁住的格取不出来，
     * 等于把物品藏进一个玩家打不开的地方。所以这里直接看玩家物品栏的占用情况，
     * 而不是看旧侧面板的储物格。
     */
    public static final class UpgradeSlot extends Slot {

        private final Inventory playerInventory;

        public UpgradeSlot(Container upgrades, Inventory playerInventory, int index, int x, int y) {
            super(upgrades, index, x, y);
            this.playerInventory = playerInventory;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.is(Items.CHEST);
        }

        @Override
        public boolean mayPickup(Player player) {
            int remaining = unlockedCountWithout(this.container, this.getContainerSlot());
            for (int i = remaining; i < MAX_ITEM_SLOTS; i++) {
                if (!this.playerInventory.getItem(i).isEmpty()) {
                    return false;
                }
            }
            return super.mayPickup(player);
        }
    }
}
