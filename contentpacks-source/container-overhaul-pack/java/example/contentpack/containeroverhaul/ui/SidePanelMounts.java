package example.contentpack.containeroverhaul.ui;

import com.catoxide.catoxidesbattlerebuild.core.inventory.PlayerInventoryLayout;
import com.catoxide.catoxidesbattlerebuild.core.ui.AuxPanels;
import com.catoxide.catoxidesbattlerebuild.core.ui.SlotBookings;
import example.contentpack.containeroverhaul.ContainerOverhaulPack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.StonecutterMenu;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * 把悬浮物品栏 / 升级板挂到原版容器菜单上。
 *
 * <p>挂载范围 = 一支明确的菜单类白名单（{@link #SUPPORTED}）：所有「玩家物品栏在容器内容
 * 下方」的原版容器屏。原版各屏的玩家主栏位置不同（箱子随行数、漏斗在 51、信标在 137），
 * 但结构一致，截线由 {@code ContainerCut} 从槽位位置推，不需要按屏写 mixin。
 *
 * <p>排除两类：商人（交易列表与玩家物品栏在同一纵向带内，无法横切）与坐骑物品栏
 * （马/羊驼/驴，槽位围绕坐骑模型排，布局不成立）。创造模式物品栏共用
 * {@code inventoryMenu}，由客户端闸门 {@link #setClientGate} 关掉。
 *
 * <p>物品格不另开存储：原版菜单已经挂上 0–35，这里只补第 4 行的 36–44（同一份
 * {@code Inventory}）。槽位不写菜单序号：背包格与升级格都经 {@link SlotBookings#book} 登记，
 * key = 面板/槽位路径，type = 背包/升级。挂载必须两侧对称：服务端用玩家常驻的升级容器，
 * 客户端放同尺寸空容器（内容随后由菜单同步灌入）。
 */
public final class SidePanelMounts {

    /**
     * 面板 id：决定与其他附属面板的挂载先后（数值小的先挂），同时是
     * {@code ui-layout.json} 里这条面板位置条目的键。
     */
    public static final ResourceLocation PANEL_ID =
            ResourceLocation.fromNamespaceAndPath(ContainerOverhaulPack.ID, "side_panel");

    /**
     * 支持的原版菜单类：玩家物品栏都在容器内容下方，结构一致。用类表而不是「有 Inventory 槽位
     * 就挂」—— 模组屏与坐骑物品栏会混进来，它们的槽位布局不成立（见类文档）。
     */
    private static final Set<Class<? extends AbstractContainerMenu>> SUPPORTED = Set.of(
            InventoryMenu.class, ChestMenu.class, ShulkerBoxMenu.class, HopperMenu.class,
            DispenserMenu.class, CraftingMenu.class, AbstractFurnaceMenu.class, BrewingStandMenu.class,
            EnchantmentMenu.class, AnvilMenu.class, GrindstoneMenu.class, LoomMenu.class,
            StonecutterMenu.class, CartographyTableMenu.class, SmithingMenu.class, CrafterMenu.class,
            BeaconMenu.class);

    /**
     * 客户端渲染/拖动用：升级容器（解锁数唯一来源）+ 菜单里全部 0–44 物品格 + 4 个升级格。
     *
     * <p>槽位坐标是纯客户端字段（不进同步包），所以拖动的实现就是「改这些 {@code x/y}」，
     * 原版 {@code findSlot} 与 {@code renderSlot} 会自动跟着走。服务端不记录本对象。
     *
     * @param hotbarY   这一屏原版快捷栏槽位的顶边（相对 guiTop）：悬浮板的快捷栏行钉在这里，
     *                  所以漏斗 / 1 行箱子 / 6 行箱子 / 信标各自和自己的面板对齐，不用按屏配表
     * @param cutHeight 截底线（相对 guiTop，=-1 表示不截）：也必须在 {@code place()} 改槽位坐标
     *                  <b>之前</b>从原版主栏位置算出来，否则第二帧以后读到的是浮板坐标
     */
    public record View(Container upgrades, List<Slot> itemSlots, List<Slot> upgradeSlots,
                       int hotbarY, int cutHeight) {}

    private static final Map<AbstractContainerMenu, View> VIEWS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 客户端出场闸门（创造屏等共用 {@code inventoryMenu} 的屏由客户端注入）。
     *
     * <p>菜单是同一份，屏不是：创造模式物品栏会按自己的 {@code SlotWrapper} 重排这些槽位，
     * 既不吃悬浮板也不吃截底。闸门放在 {@link #view} 上，关掉之后渲染、拖动、容量一起回到原版。
     */
    private static Predicate<AbstractContainerMenu> clientGate = menu -> true;

    private SidePanelMounts() {}

    public static void register() {
        AuxPanels.register(PANEL_ID, 0, SidePanelMounts::attach);
    }

    public static void setClientGate(Predicate<AbstractContainerMenu> gate) {
        clientGate = gate;
    }

    @Nullable
    public static View view(@Nullable AbstractContainerMenu menu) {
        if (menu == null || !clientGate.test(menu)) {
            return null;
        }
        return VIEWS.get(menu);
    }

    private static void attach(AbstractContainerMenu menu, Inventory playerInventory) {
        if (SUPPORTED.stream().noneMatch(type -> type.isInstance(menu))) {
            return;
        }
        Player player = playerInventory.player;
        boolean client = player.level().isClientSide;
        Container upgrades = client ? new SimpleContainer(SidePanel.UPGRADE_COLS) : Upgrades.of(player);
        int hotbarY = hotbarY(menu, playerInventory);
        int cutHeight = cutHeight(menu, playerInventory);

        for (int col = 0; col < SidePanel.EXTRA_ROW_SLOTS; col++) {
            int containerSlot = SidePanel.EXTRA_ROW_START + col;
            SlotBookings.book(menu, SidePanel.backpackKey(containerSlot), SidePanel.BACKPACK_TYPE,
                    new Slot(playerInventory, containerSlot,
                            SidePanel.slotX(SidePanel.DEFAULT_GUI_WIDTH, containerSlot),
                            SidePanel.slotY(hotbarY, containerSlot)));
        }

        List<Slot> upgradeSlots = new ArrayList<>(SidePanel.UPGRADE_COLS);
        for (int col = 0; col < SidePanel.UPGRADE_COLS; col++) {
            upgradeSlots.add(SlotBookings.book(menu, SidePanel.upgradeKey(col), SidePanel.UPGRADE_TYPE,
                    new SidePanel.UpgradeSlot(upgrades, playerInventory, col,
                            SidePanel.upgradeSlotX(SidePanel.DEFAULT_GUI_WIDTH, col),
                            SidePanel.upgradeSlotY(hotbarY))));
        }

        if (client) {
            VIEWS.put(menu, new View(upgrades, itemSlots(menu, playerInventory),
                    List.copyOf(upgradeSlots), hotbarY, cutHeight));
        }
    }

    /** 原版快捷栏（容器下标 0–8）在这一屏里的顶边；菜单里找不到时退回标准屏的位置。 */
    private static int hotbarY(AbstractContainerMenu menu, Inventory playerInventory) {
        int top = Integer.MAX_VALUE;
        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory
                    && PlayerInventoryLayout.isHotbarSlot(slot.getContainerSlot())) {
                top = Math.min(top, slot.y);
            }
        }
        return top == Integer.MAX_VALUE ? SidePanel.DEFAULT_HOTBAR_Y : top;
    }

    /**
     * 原版玩家主栏（容器下标 9–35）在这一屏里的最高顶边 - 1 = 截底线；找不到返回 -1（不截）。
     *
     * <p>只在挂载时算一次：之后 {@code SidePanelLayout.place} 每帧把这些槽位挪到浮板上，
     * 再读 {@code slot.y} 拿到的是浮板坐标，不是原版位置。
     */
    private static int cutHeight(AbstractContainerMenu menu, Inventory playerInventory) {
        int mainTop = Integer.MAX_VALUE;
        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory
                    && SidePanel.isVanillaMainSlot(slot.getContainerSlot())) {
                mainTop = Math.min(mainTop, slot.y);
            }
        }
        return mainTop == Integer.MAX_VALUE ? -1 : mainTop - 1;
    }

    /** 菜单里全部属于玩家物品栏的 0–44 格（原版 0–35 + 补挂的 36–44），按容器下标排序。 */
    private static List<Slot> itemSlots(AbstractContainerMenu menu, Inventory playerInventory) {
        return menu.slots.stream()
                .filter(slot -> slot.container == playerInventory
                        && PlayerInventoryLayout.isItemSlot(slot.getContainerSlot()))
                .sorted(Comparator.comparingInt(Slot::getContainerSlot))
                .toList();
    }
}
