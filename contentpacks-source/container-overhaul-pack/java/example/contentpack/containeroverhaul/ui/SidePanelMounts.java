package example.contentpack.containeroverhaul.ui;

import com.catoxide.catoxidesbattlerebuild.core.ui.AuxPanels;
import com.catoxide.catoxidesbattlerebuild.core.ui.SlotBookings;
import example.contentpack.containeroverhaul.ContainerOverhaulPack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 把 {@link SidePanel} 挂到原版容器菜单上。
 *
 * <p>本切片只挂玩家物品栏与箱子（含木桶，都是 {@link ChestMenu}）；扩大到更多容器类型只是
 * 往这里加判断，不需要按屏写 mixin —— 菜单侧挂载点由 core 的
 * {@code MenuType#create} 与 {@code InventoryMenu} 构造器统一提供。
 *
 * <p>槽位不写序号：逐个交给 {@link SlotBookings#book}，key = 面板/槽位路径，type = 暂存/升级。
 * 挂载必须两侧对称：服务端取存档里的容器，客户端放同尺寸空容器（内容随后由菜单包灌入）。
 */
public final class SidePanelMounts {

    /**
     * 面板 id：决定与其他附属面板的挂载先后（数值小的先挂），同时是
     * {@code ui-layout.json} 里这条面板位置条目的键。
     */
    public static final ResourceLocation PANEL_ID =
            ResourceLocation.fromNamespaceAndPath(ContainerOverhaulPack.ID, "side_panel");

    /**
     * 客户端渲染/拖动用：面板内容容器 + 这次挂载追加的槽位。
     *
     * <p>槽位坐标是纯客户端字段（不进同步包），所以拖动的实现就是「改这些 {@code x/y}」，
     * 原版 {@code findSlot} 与 {@code renderSlot} 会自动跟着走。服务端不记录本对象。
     */
    public record View(Container container, List<Slot> slots) {}

    private static final Map<AbstractContainerMenu, View> VIEWS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SidePanelMounts() {}

    public static void register() {
        AuxPanels.register(PANEL_ID, 0, SidePanelMounts::attach);
    }

    @Nullable
    public static View view(AbstractContainerMenu menu) {
        return VIEWS.get(menu);
    }

    private static void attach(AbstractContainerMenu menu, Inventory playerInventory) {
        if (!(menu instanceof InventoryMenu) && !(menu instanceof ChestMenu)) {
            return;
        }
        Player player = playerInventory.player;
        boolean client = player.level().isClientSide;
        Container container = client ? new SimpleContainer(SidePanel.COUNT) : PanelStorage.containerFor(player);

        List<Slot> booked = new ArrayList<>(SidePanel.COUNT);
        for (int row = 0; row < SidePanel.STORAGE_ROWS; row++) {
            for (int col = 0; col < SidePanel.STORAGE_COLS; col++) {
                int index = row * SidePanel.STORAGE_COLS + col;
                booked.add(SlotBookings.book(menu, SidePanel.storageKey(index), SidePanel.STORAGE_TYPE,
                        new SidePanel.StorageSlot(container, index,
                                SidePanel.slotX(index), SidePanel.slotY(index))));
            }
        }
        for (int col = 0; col < SidePanel.UPGRADE_COLS; col++) {
            int index = SidePanel.UPGRADE_START + col;
            booked.add(SlotBookings.book(menu, SidePanel.upgradeKey(col), SidePanel.UPGRADE_TYPE,
                    new SidePanel.UpgradeSlot(container, index,
                            SidePanel.slotX(index), SidePanel.slotY(index))));
        }

        if (client) {
            VIEWS.put(menu, new View(container, List.copyOf(booked)));
        }
    }
}
