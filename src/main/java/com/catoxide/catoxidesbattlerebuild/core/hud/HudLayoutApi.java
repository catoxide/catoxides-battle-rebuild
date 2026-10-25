package com.catoxide.catoxidesbattlerebuild.core.hud;

import net.minecraft.resources.ResourceLocation;

/**
 * 布局服务定位器：把「当前生效的偏移」交给任意内容包查询。
 *
 * <p>实现方是 HUD 布局包（它持有持久化与编辑屏）；使用方是任何自绘 HUD 的包。
 * 没有布局包时全部返回 0——自绘 HUD 退回原位置，行为与未安装布局包一致（R3）。
 *
 * <p>读接口按元素 id 调用即可，内部按 {@link HudElement#group()} 折叠到组偏移；
 * 未登记的元素 id 直接当作组键处理。
 */
public final class HudLayoutApi {

    /** 由布局包实现。参数为分组键（{@link HudElement#group()}）。 */
    public interface Provider {

        int offsetX(ResourceLocation group);

        int offsetY(ResourceLocation group);

        boolean isCustomized(ResourceLocation group);
    }

    private static volatile Provider provider;

    private HudLayoutApi() {
    }

    /** 由布局包在 {@code register()} 阶段调用一次；后装者覆盖先装者。 */
    public static void install(Provider layoutProvider) {
        provider = layoutProvider;
    }

    public static boolean isAvailable() {
        return provider != null;
    }

    public static int offsetX(ResourceLocation elementOrGroup) {
        Provider current = provider;
        return current == null ? 0 : current.offsetX(resolveGroup(elementOrGroup));
    }

    public static int offsetY(ResourceLocation elementOrGroup) {
        Provider current = provider;
        return current == null ? 0 : current.offsetY(resolveGroup(elementOrGroup));
    }

    public static boolean isCustomized(ResourceLocation elementOrGroup) {
        Provider current = provider;
        return current != null && current.isCustomized(resolveGroup(elementOrGroup));
    }

    private static ResourceLocation resolveGroup(ResourceLocation elementOrGroup) {
        ResourceLocation group = HudElements.groupOf(elementOrGroup);
        return group == null ? elementOrGroup : group;
    }
}
