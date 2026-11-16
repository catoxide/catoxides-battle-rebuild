package example.contentpack.containeroverhaul.ui;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 原版容器屏右侧的附属面板：3×3 暂存 + 2 个升级槽。
 *
 * <p>坐标是菜单坐标 —— 原版 {@code AbstractContainerScreen#render} 在画槽位前把 Pose 平移到
 * {@code leftPos/topPos}，所以槽位和贴图都用同一套局部坐标。贴图由
 * {@code ui-specs/side_panel.json} 生成，格点原点与这里的 {@code STORAGE_*}/{@code UPGRADE_*}
 * 一致，槽位比格点右下各 1px（原版 {@code renderSlot} 画在 {@code slot.x - 1}）。
 *
 * <p>暂存格按升级解锁：基础一排 3 格，每个升级槽里的箱子 +3 格。
 *
 * <p>槽位不写菜单序号：每个槽位用 {@link #storageKey(int)}/{@link #upgradeKey(int)} 的 key
 * 与 {@link #STORAGE_TYPE}/{@link #UPGRADE_TYPE} 类型交给
 * {@code SlotBookings} 登记，序号由登记处分配。
 */
public final class SidePanel {

    public static final String NAMESPACE = "container-overhaul-pack";
    public static final ResourceLocation STORAGE_TYPE = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "side_panel/storage");
    public static final ResourceLocation UPGRADE_TYPE = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "side_panel/upgrade");

    public static final int PANEL_X = 180;
    public static final int PANEL_Y = 0;
    public static final int WIDTH = 62;
    public static final int HEIGHT = 84;

    public static final int SLOT_PITCH = 18;
    public static final int STORAGE_COLS = 3;
    public static final int STORAGE_ROWS = 3;
    public static final int STORAGE_X = 4;
    public static final int STORAGE_Y = 4;

    public static final int UPGRADE_COLS = 2;
    public static final int UPGRADE_X = 4;
    public static final int UPGRADE_Y = 62;

    public static final int STORAGE_COUNT = STORAGE_COLS * STORAGE_ROWS;
    public static final int UPGRADE_START = STORAGE_COUNT;
    public static final int COUNT = STORAGE_COUNT + UPGRADE_COLS;

    public static final int BASE_UNLOCKED = STORAGE_COLS;
    public static final int UNLOCK_PER_UPGRADE = STORAGE_COLS;

    private SidePanel() {}

    public static ResourceLocation storageKey(int index) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, "side_panel/storage/" + index);
    }

    public static ResourceLocation upgradeKey(int index) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, "side_panel/upgrade/" + index);
    }

    public static int unlockedCount(Container container) {
        int unlocked = BASE_UNLOCKED;
        for (int i = UPGRADE_START; i < COUNT; i++) {
            if (!container.getItem(i).isEmpty()) {
                unlocked += UNLOCK_PER_UPGRADE;
            }
        }
        return Math.min(unlocked, STORAGE_COUNT);
    }

    /**
     * 面板槽位在菜单坐标里的基准位置；拖动时由客户端在这之上加偏移。
     *
     * <p>按容器下标算而不是按列行算：拖动要通过 {@code getContainerSlot()} 反推位置，
     * 暂存格（0..8）与升级槽（9..10）共用这一条路径，坐标只在这一个地方定义。
     */
    public static int slotX(int containerSlot) {
        if (containerSlot < STORAGE_COUNT) {
            return PANEL_X + STORAGE_X + 1 + (containerSlot % STORAGE_COLS) * SLOT_PITCH;
        }
        return PANEL_X + UPGRADE_X + 1 + (containerSlot - UPGRADE_START) * SLOT_PITCH;
    }

    public static int slotY(int containerSlot) {
        if (containerSlot < STORAGE_COUNT) {
            return PANEL_Y + STORAGE_Y + 1 + (containerSlot / STORAGE_COLS) * SLOT_PITCH;
        }
        return PANEL_Y + UPGRADE_Y + 1;
    }

    public static final class StorageSlot extends Slot {

        public StorageSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return this.isActive() && super.mayPlace(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return this.isActive() && super.mayPickup(player);
        }

        @Override
        public boolean isActive() {
            return this.getContainerSlot() < unlockedCount(this.container);
        }
    }

    public static final class UpgradeSlot extends Slot {

        public UpgradeSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.is(Items.CHEST);
        }

        /** 拔掉升级会锁住后面的暂存格；那些格里有东西时不许拔。 */
        @Override
        public boolean mayPickup(Player player) {
            int remaining = unlockedCountWithout(this.getContainerSlot());
            for (int i = remaining; i < STORAGE_COUNT; i++) {
                if (!this.container.getItem(i).isEmpty()) {
                    return false;
                }
            }
            return super.mayPickup(player);
        }

        private int unlockedCountWithout(int upgradeIndex) {
            int unlocked = BASE_UNLOCKED;
            for (int i = UPGRADE_START; i < COUNT; i++) {
                if (i != upgradeIndex && !this.container.getItem(i).isEmpty()) {
                    unlocked += UNLOCK_PER_UPGRADE;
                }
            }
            return Math.min(unlocked, STORAGE_COUNT);
        }
    }
}
