package com.catoxide.catoxidesbattlerebuild.core.ui;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 槽位登记处：调用方只声明「这个槽位是什么 key、什么类型」，
 * 菜单内的序号由这里按追加顺序分配并记死；业务代码永远不写序号。
 *
 * <p>兼容模型（这是能协调到什么程度的边界）：
 * <ul>
 *   <li><b>只追加、不插队</b>。原版把物品栏 0..45、容器 {@code rows*9} 等固定序号当契约，
 *       任何插队都会破坏原版 {@code quickMoveStack}，所以附属槽位一律排在最后；</li>
 *   <li><b>序号在登记时记死</b>。之后别的模组再往菜单追加槽位，不会改变已登记的号；</li>
 *   <li><b>顺序确定</b>。同一菜单的登记顺序 = {@link AuxPanels} 注册的 {@code (priority, id)}，
 *       再按各面板自己的声明顺序；两侧走同一路径，所以客户端/服务端序号一致；</li>
 *   <li><b>key 带命名空间</b>。重复登记直接抛错，两个包不会悄悄抢同一个语义槽。</li>
 * </ul>
 *
 * <p>协调不到的部分：不经过本登记处、自己往 {@code slots} 里塞位置的模组。它们一旦插队，
 * 原版契约本身就会坏，不是这里能补救的；我们能保证的是自己永远按 key 取槽、从不假设序号。
 */
public final class SlotBookings {

    public record Booking(ResourceLocation key, ResourceLocation type, Slot slot) {}

    private static final Map<AbstractContainerMenu, Bookings> BY_MENU =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SlotBookings() {}

    /** 追加一个槽位并登记；返回追加后的槽位（序号已由菜单分配）。 */
    public static Slot book(AbstractContainerMenu menu, ResourceLocation key, ResourceLocation type, Slot slot) {
        Bookings bookings = BY_MENU.computeIfAbsent(menu, ignored -> new Bookings());
        synchronized (bookings) {
            if (bookings.byKey.containsKey(key)) {
                throw new IllegalStateException("duplicate slot key: " + key);
            }
            Slot added = AuxPanels.addSlot(menu, slot);
            Booking booking = new Booking(key, type, added);
            bookings.byKey.put(key, booking);
            bookings.byType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(booking);
            return added;
        }
    }

    @Nullable
    public static Booking booking(AbstractContainerMenu menu, ResourceLocation key) {
        Bookings bookings = BY_MENU.get(menu);
        if (bookings == null) {
            return null;
        }
        synchronized (bookings) {
            return bookings.byKey.get(key);
        }
    }

    /** 按 key 取槽并校验类型；查不到或类型不符都是调用方写错，直接抛。 */
    public static Slot require(AbstractContainerMenu menu, ResourceLocation key, ResourceLocation type) {
        Booking booking = booking(menu, key);
        if (booking == null) {
            throw new IllegalArgumentException("slot key not booked: " + key);
        }
        if (!booking.type().equals(type)) {
            throw new IllegalStateException("slot " + key + " has type " + booking.type() + ", expected " + type);
        }
        return booking.slot();
    }

    public static int index(AbstractContainerMenu menu, ResourceLocation key) {
        Booking booking = booking(menu, key);
        if (booking == null) {
            throw new IllegalArgumentException("slot key not booked: " + key);
        }
        return booking.slot().index;
    }

    public static List<Booking> bookings(AbstractContainerMenu menu, ResourceLocation type) {
        Bookings bookings = BY_MENU.get(menu);
        if (bookings == null) {
            return List.of();
        }
        synchronized (bookings) {
            return List.copyOf(bookings.byType.getOrDefault(type, List.of()));
        }
    }

    public static List<Slot> slots(AbstractContainerMenu menu, ResourceLocation type) {
        return bookings(menu, type).stream().map(Booking::slot).toList();
    }

    public static List<Integer> indices(AbstractContainerMenu menu, ResourceLocation type) {
        return bookings(menu, type).stream().map(booking -> booking.slot().index).toList();
    }

    public static List<ResourceLocation> keys(AbstractContainerMenu menu) {
        Bookings bookings = BY_MENU.get(menu);
        if (bookings == null) {
            return List.of();
        }
        synchronized (bookings) {
            return List.copyOf(bookings.byKey.keySet());
        }
    }

    private static final class Bookings {
        final Map<ResourceLocation, Booking> byKey = new LinkedHashMap<>();
        final Map<ResourceLocation, List<Booking>> byType = new LinkedHashMap<>();
    }
}
