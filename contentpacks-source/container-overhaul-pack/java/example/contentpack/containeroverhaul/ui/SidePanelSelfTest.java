package example.contentpack.containeroverhaul.ui;

import com.catoxide.catoxidesbattlerebuild.core.inventory.PlayerInventoryLayout;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 悬浮物品栏的启动自检：解锁阶梯、行数、行号映射、快捷栏锚点、槽位是否都在板内、
 * 与 core 常量的一致性、拔升级守卫、旧存档迁移映射。
 *
 * <p>检查的是「错了一眼看不出来、但会让玩家丢东西」的部分。失败用 ERROR 记，
 * 由 {@code scripts/validate-game.ps1} 当成 FAIL 抓出来。贴图与格点的对应关系另有
 * {@code ui-specs/verify-alignment.mjs}。
 */
public final class SidePanelSelfTest {

    private static final String TAG = "SidePanel";

    private SidePanelSelfTest() {}

    public static void run() {
        int passed = 0;
        int failed = 0;
        for (Map.Entry<String, Runnable> test : tests().entrySet()) {
            try {
                test.getValue().run();
                passed++;
            } catch (Throwable error) {
                failed++;
                LogManager.serverError(TAG, "自检失败 [%s]: %s", test.getKey(), error);
            }
        }
        if (failed == 0) {
            LogManager.serverInfo(TAG, "悬浮物品栏自检通过（%d 项）", passed);
        } else {
            LogManager.serverError(TAG, "悬浮物品栏自检 %d 项失败 / 共 %d 项", failed, passed + failed);
        }
    }

    private static Map<String, Runnable> tests() {
        Map<String, Runnable> tests = new LinkedHashMap<>();
        tests.put("解锁阶梯 9/18/27/36/45", SidePanelSelfTest::unlockLadder);
        tests.put("行数 1–5", SidePanelSelfTest::rowLadder);
        tests.put("行号映射", SidePanelSelfTest::rowMapping);
        tests.put("快捷栏钉底 / 板顶随行数上移", SidePanelSelfTest::hotbarAnchored);
        tests.put("槽位都在板内", SidePanelSelfTest::slotsInsideBoard);
        tests.put("与 core 常量一致", SidePanelSelfTest::coreParity);
        tests.put("拔升级不锁住内容", SidePanelSelfTest::upgradePickupGuard);
        tests.put("旧存档迁移映射", SidePanelSelfTest::migrationMapping);
        return tests;
    }

    private static void unlockLadder() {
        SimpleContainer upgrades = new SimpleContainer(SidePanel.UPGRADE_COLS);
        check(SidePanel.unlockedCount(upgrades) == SidePanel.BASE_UNLOCKED,
                "空升级槽时解锁数应为 " + SidePanel.BASE_UNLOCKED);
        for (int i = 0; i < SidePanel.UPGRADE_COLS; i++) {
            upgrades.setItem(i, new ItemStack(Items.CHEST));
            int expected = Math.min(SidePanel.BASE_UNLOCKED + (i + 1) * SidePanel.UNLOCK_PER_UPGRADE,
                    SidePanel.MAX_ITEM_SLOTS);
            check(SidePanel.unlockedCount(upgrades) == expected,
                    "第 " + (i + 1) + " 个升级后应为 " + expected + " 格");
        }
        check(SidePanel.unlockedCount(upgrades) == SidePanel.MAX_ITEM_SLOTS,
                "升级槽放满后应封顶到 " + SidePanel.MAX_ITEM_SLOTS);
    }

    private static void rowLadder() {
        SimpleContainer upgrades = new SimpleContainer(SidePanel.UPGRADE_COLS);
        check(SidePanel.backpackRows(upgrades) == 0 && SidePanel.boardRows(upgrades) == 1,
                "空升级槽时应只有快捷栏一行");
        for (int i = 0; i < SidePanel.UPGRADE_COLS; i++) {
            upgrades.setItem(i, new ItemStack(Items.CHEST));
            check(SidePanel.backpackRows(upgrades) == i + 1,
                    "第 " + (i + 1) + " 个升级后应有 " + (i + 1) + " 行背包");
            check(SidePanel.boardRows(upgrades) == i + 2,
                    "第 " + (i + 1) + " 个升级后板高应为 " + (i + 2) + " 行");
        }
        check(SidePanel.boardRows(upgrades) == SidePanel.BACKPACK_ROWS + 1,
                "升级槽放满后板高应封顶到 " + (SidePanel.BACKPACK_ROWS + 1) + " 行");
    }

    private static void rowMapping() {
        check(SidePanel.slotRow(0) == 0 && SidePanel.slotRow(8) == 0, "0–8 应为快捷栏行");
        check(SidePanel.slotRow(9) == 1 && SidePanel.slotRow(17) == 1, "9–17 应为背包第 1 行");
        check(SidePanel.slotRow(27) == 3 && SidePanel.slotRow(35) == 3, "27–35 应为背包第 3 行");
        check(SidePanel.slotRow(SidePanel.EXTRA_ROW_START) == 4
                        && SidePanel.slotRow(SidePanel.MAX_ITEM_SLOTS - 1) == 4,
                "36–44 应为背包第 4 行");
    }

    private static void hotbarAnchored() {
        int hotbarY = SidePanel.DEFAULT_HOTBAR_Y;
        check(SidePanel.slotY(hotbarY, 0) == hotbarY + 1,
                "快捷栏不在 hotbarY 那一行");
        for (int rows = 1; rows <= SidePanel.BACKPACK_ROWS + 1; rows++) {
            int gap = rows > 1 ? SidePanel.HOTBAR_GAP : 0;
            check(SidePanel.boardY(hotbarY, rows) + (rows - 1) * SidePanel.ROW_PITCH
                            + gap + SidePanel.ROW_INSET == hotbarY,
                    rows + " 行时板底没有落在快捷栏行");
            if (rows > 1) {
                check(SidePanel.boardY(hotbarY, rows) < SidePanel.boardY(hotbarY, rows - 1),
                        "行数增加时板顶没有往上移");
            }
        }
        // 快捷栏与第 1 行背包之间要多让开 HOTBAR_GAP；背包行之间仍紧贴 18。
        int hotbarTop = SidePanel.slotY(hotbarY, 0);
        int firstBackpackTop = SidePanel.slotY(hotbarY, SidePanel.ROW_SLOTS);
        int secondBackpackTop = SidePanel.slotY(hotbarY, SidePanel.ROW_SLOTS * 2);
        check(firstBackpackTop + SidePanel.SLOT_PITCH + SidePanel.HOTBAR_GAP == hotbarTop,
                "快捷栏与背包第 1 行的间距不是 SLOT_PITCH + HOTBAR_GAP");
        check(secondBackpackTop + SidePanel.SLOT_PITCH == firstBackpackTop,
                "背包行之间不再是 SLOT_PITCH 紧贴（" + firstBackpackTop + " -> " + secondBackpackTop + "）");
    }

    private static void slotsInsideBoard() {
        int hotbarY = SidePanel.DEFAULT_HOTBAR_Y;
        int boardX = SidePanel.boardX(SidePanel.DEFAULT_GUI_WIDTH);
        for (int rows = 1; rows <= SidePanel.BACKPACK_ROWS + 1; rows++) {
            int top = SidePanel.boardY(hotbarY, rows);
            int bottom = top + SidePanel.boardHeight(rows);
            for (int slot = 0; slot < rows * SidePanel.ROW_SLOTS; slot++) {
                int x = SidePanel.slotX(SidePanel.DEFAULT_GUI_WIDTH, slot);
                int y = SidePanel.slotY(hotbarY, slot);
                check(x >= boardX && x + 16 <= boardX + SidePanel.ROW_WIDTH,
                        "槽位 " + slot + " 横向出板: x=" + x);
                check(y >= top && y + 16 <= bottom, "槽位 " + slot + " 纵向出板: y=" + y);
            }
        }
        check(SidePanel.upgradeSlotY(hotbarY) + 16
                        <= SidePanel.upgradePanelY(hotbarY) + SidePanel.UPGRADE_HEIGHT,
                "升级槽纵向出升级板");
        check(SidePanel.upgradeSlotX(SidePanel.DEFAULT_GUI_WIDTH, SidePanel.UPGRADE_COLS - 1) + 16
                        <= boardX + SidePanel.UPGRADE_WIDTH,
                "升级槽横向出升级板");
    }

    private static void coreParity() {
        check(SidePanel.MAX_ITEM_SLOTS == PlayerInventoryLayout.ITEM_SLOTS,
                "包内物品格数 " + SidePanel.MAX_ITEM_SLOTS + " ≠ core " + PlayerInventoryLayout.ITEM_SLOTS);
        check(SidePanel.EXTRA_ROW_START + SidePanel.EXTRA_ROW_SLOTS == PlayerInventoryLayout.ARMOR_START,
                "补挂的背包行没有接在护甲区前面");
    }

    private static void upgradePickupGuard() {
        SimpleContainer upgrades = new SimpleContainer(SidePanel.UPGRADE_COLS);
        Inventory inventory = new Inventory(null);
        SidePanel.UpgradeSlot upgrade = new SidePanel.UpgradeSlot(upgrades, inventory, 0, 0, 0);
        check(upgrade.mayPlace(new ItemStack(Items.CHEST)), "升级槽应收箱子");
        check(!upgrade.mayPlace(new ItemStack(Items.STONE)), "升级槽不该收别的东西");
        check(upgrade.mayPickup(null), "空物品栏里升级槽应可拔");

        upgrades.setItem(0, new ItemStack(Items.CHEST));
        inventory.setItem(SidePanel.BASE_UNLOCKED, new ItemStack(Items.STONE));
        check(!upgrade.mayPickup(null), "有物品占着被解锁的格时不能拔升级");

        inventory.setItem(SidePanel.BASE_UNLOCKED, ItemStack.EMPTY);
        check(upgrade.mayPickup(null), "腾空被解锁的格后应可拔");
    }

    private static void migrationMapping() {
        check(InventoryMigration.migratedSlot(0) == SidePanel.ROW_SLOTS, "旧 0 应迁到 9");
        check(InventoryMigration.migratedSlot(27) == 36, "旧 27 应迁到 36");
        check(InventoryMigration.migratedSlot(36) == -1, "旧 36 没有对应格");
        check(InventoryMigration.migratedSlot(SidePanel.MAX_ITEM_SLOTS) == -1, "越界应为 -1");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
