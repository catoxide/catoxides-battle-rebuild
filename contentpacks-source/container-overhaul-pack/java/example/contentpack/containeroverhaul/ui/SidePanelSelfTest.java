package example.contentpack.containeroverhaul.ui;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 侧面板的启动自检：解锁阶梯、锁格的拒绝、拔升级的守卫、槽位是否都在面板矩形内。
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
            LogManager.serverInfo(TAG, "侧面板自检通过（%d 项）", passed);
        } else {
            LogManager.serverError(TAG, "侧面板自检 %d 项失败 / 共 %d 项", failed, passed + failed);
        }
    }

    private static Map<String, Runnable> tests() {
        Map<String, Runnable> tests = new LinkedHashMap<>();
        tests.put("解锁阶梯 3/6/9", SidePanelSelfTest::unlockLadder);
        tests.put("锁住的格拒绝放入", SidePanelSelfTest::lockedSlotsRejectInsertion);
        tests.put("拔升级不锁住内容", SidePanelSelfTest::upgradePickupGuard);
        tests.put("槽位都在面板矩形内", SidePanelSelfTest::slotsInsidePanel);
        return tests;
    }

    private static void unlockLadder() {
        SimpleContainer container = new SimpleContainer(SidePanel.COUNT);
        check(SidePanel.unlockedCount(container) == SidePanel.BASE_UNLOCKED,
                "空升级槽时解锁数应为 " + SidePanel.BASE_UNLOCKED);
        container.setItem(SidePanel.UPGRADE_START, new ItemStack(Items.CHEST));
        check(SidePanel.unlockedCount(container) == 6, "一个升级应为 6 格");
        container.setItem(SidePanel.UPGRADE_START + 1, new ItemStack(Items.CHEST));
        check(SidePanel.unlockedCount(container) == SidePanel.STORAGE_COUNT, "两个升级应封顶到 9 格");
    }

    private static void lockedSlotsRejectInsertion() {
        SimpleContainer container = new SimpleContainer(SidePanel.COUNT);
        Slot locked = new SidePanel.StorageSlot(container, SidePanel.BASE_UNLOCKED, 0, 0);
        check(!locked.isActive(), "第 " + (SidePanel.BASE_UNLOCKED + 1) + " 格在无升级时应是锁的");
        check(!locked.mayPlace(new ItemStack(Items.STONE)), "锁住的格不能放入");
        check(!locked.mayPickup(null), "锁住的格不能取出");

        Slot open = new SidePanel.StorageSlot(container, 0, 0, 0);
        check(open.isActive() && open.mayPlace(new ItemStack(Items.STONE)), "基础格应可放可取");
    }

    private static void upgradePickupGuard() {
        SimpleContainer container = new SimpleContainer(SidePanel.COUNT);
        SidePanel.UpgradeSlot upgrade = new SidePanel.UpgradeSlot(container, SidePanel.UPGRADE_START, 0, 0);
        check(upgrade.mayPickup(null), "空存档里升级槽应可拔");

        container.setItem(SidePanel.UPGRADE_START, new ItemStack(Items.CHEST));
        container.setItem(SidePanel.BASE_UNLOCKED, new ItemStack(Items.STONE));
        check(!upgrade.mayPickup(null), "有物品占着被解锁的格时不能拔升级");

        container.setItem(SidePanel.BASE_UNLOCKED, ItemStack.EMPTY);
        check(upgrade.mayPickup(null), "腾空被解锁的格后应可拔");
    }

    private static void slotsInsidePanel() {
        for (int i = 0; i < SidePanel.COUNT; i++) {
            int x = SidePanel.slotX(i);
            int y = SidePanel.slotY(i);
            check(x >= SidePanel.PANEL_X && x + 16 <= SidePanel.PANEL_X + SidePanel.WIDTH,
                    "槽位 " + i + " 横向出面板: x=" + x);
            check(y >= SidePanel.PANEL_Y && y + 16 <= SidePanel.PANEL_Y + SidePanel.HEIGHT,
                    "槽位 " + i + " 纵向出面板: y=" + y);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
