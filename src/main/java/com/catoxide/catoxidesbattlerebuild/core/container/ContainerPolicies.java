package com.catoxide.catoxidesbattlerebuild.core.container;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 容器策略注册表（仿 {@code HurtInterceptors}：core 只做登记与遍历，规则住内容包）。
 *
 * <p>被两处 mixin 查询：{@code Slot#mayPlace}（GUI 点击/Shift 点击）与
 * {@code RandomizableContainerBlockEntity#canPlaceItem}（漏斗/管道等自动化）。
 * 两处都必须拦，因为原版 {@code Slot#mayPlace} 默认恒为 {@code true}，不看容器。
 *
 * <p>R3：注册表为空时所有查询返回 {@code false}，行为 = 原版。
 */
public final class ContainerPolicies {

    private static final String TAG = "ContainerPolicies";
    private static final List<IContainerPolicy> POLICIES = new CopyOnWriteArrayList<>();

    private ContainerPolicies() {}

    public static void register(IContainerPolicy policy) {
        POLICIES.add(policy);
        LogManager.serverInfo(TAG, "ContainerPolicy registered: {}", policy.getClass().getName());
    }

    public static boolean blocksInsertion(Container container, int slot, ItemStack stack) {
        if (container == null || POLICIES.isEmpty()) {
            return false;
        }
        for (IContainerPolicy policy : POLICIES) {
            if (policy.blocksInsertion(container, slot, stack)) {
                return true;
            }
        }
        return false;
    }
}
