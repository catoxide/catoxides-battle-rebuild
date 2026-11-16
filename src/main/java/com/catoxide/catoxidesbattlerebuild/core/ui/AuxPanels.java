package com.catoxide.catoxidesbattlerebuild.core.ui;

import com.catoxide.catoxidesbattlerebuild.mixin.AbstractContainerMenuInvoker;
import com.catoxide.catoxidesbattlerebuild.mixin.SlotPositionAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 容器「附属窗」（附加槽位组）的挂载点。
 *
 * <p>一个屏幕只有一个菜单：附属窗的槽位必须加进当前 {@link AbstractContainerMenu}，
 * 拖拽 / 点击 / 悬停才会走原版 {@code AbstractContainerScreen} 的槽位逻辑。核心只提供挂载点，
 * 面板内容、登记与渲染都在内容包。
 *
 * <p>挂载时机由 mixin 决定：{@code MenuType#create} 覆盖所有经类型创建的原版容器菜单，
 * {@code InventoryMenu} 的构造器单独覆盖玩家物品栏。客户端与服务端都会执行，所以
 * {@link Attacher} 必须按同一条件追加同样数量的槽位，否则首包内容同步就会错位。
 *
 * <p>多个内容包都会挂面板，顺序必须两侧一致：注册按 {@code (priority, id)} 排序，
 * 数值小的先挂。槽位序号由 {@link SlotBookings} 分配，不要在 {@link Attacher} 里写死。
 */
public final class AuxPanels {

    @FunctionalInterface
    public interface Attacher {
        void attach(AbstractContainerMenu menu, Inventory playerInventory);
    }

    public record Registration(ResourceLocation id, int priority, Attacher attacher) {}

    private static final List<Registration> REGISTRATIONS = new CopyOnWriteArrayList<>();
    private static final Set<AbstractContainerMenu> ATTACHED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private AuxPanels() {}

    public static void register(ResourceLocation id, int priority, Attacher attacher) {
        for (Registration existing : REGISTRATIONS) {
            if (existing.id().equals(id)) {
                throw new IllegalStateException("duplicate aux panel id: " + id);
            }
        }
        REGISTRATIONS.add(new Registration(id, priority, attacher));
        REGISTRATIONS.sort(Comparator.comparingInt(Registration::priority)
                .thenComparing(registration -> registration.id().toString()));
    }

    /**
     * 幂等：NeoForge 的 3 参 {@code create} 在非自定义工厂时会回落到 2 参，
     * 两条路径都会经过 mixin，不能挂两次。
     */
    public static void attachAll(AbstractContainerMenu menu, Inventory playerInventory) {
        if (menu == null || playerInventory == null || !ATTACHED.add(menu)) {
            return;
        }
        for (Registration registration : REGISTRATIONS) {
            registration.attacher().attach(menu, playerInventory);
        }
    }

    /**
     * 追加槽位：走 {@code addSlot}（同时登记 lastSlots/remoteSlots，直接改 slots 不会同步）。
     *
     * <p>只给 {@link SlotBookings} 用 —— 包侧必须经登记处拿槽位，才能拿到稳定 key 与类型。
     */
    static Slot addSlot(AbstractContainerMenu menu, Slot slot) {
        return ((AbstractContainerMenuInvoker) menu).catoxides$addSlot(slot);
    }

    /**
     * 挪动已挂载槽位的显示位置（面板拖动）。
     *
     * <p>{@code Slot.x/y} 是 {@code final}，只能经 core 的 {@code SlotPositionAccess} 改；
     * 这两个值只被客户端读（{@code findSlot}/{@code renderSlot}），服务端按 {@code slot.index}
     * 处理点击，所以单侧改动不会造成不同步。
     */
    public static void moveSlot(Slot slot, int x, int y) {
        SlotPositionAccess access = (SlotPositionAccess) (Object) slot;
        access.catoxides$setX(x);
        access.catoxides$setY(y);
    }
}
