package com.catoxide.catoxidesbattlerebuild.core.durability;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

/**
 * 耐久降级处理器注册表
 * <p>由 durability contentpack 在 init() 阶段注册 {@link IDurabilityDegradationHandler} 实现。
 * 主 mod 的降级 mixin（EnchantmentHelperMixin 等）通过本注册表调用处理逻辑；
 * 未注册时（插件未加载）mixin 放行，原版行为不变。
 */
public final class DurabilityDegradationRegistry {

    private static final String TAG = "DurabilityDegradationRegistry";
    private static volatile IDurabilityDegradationHandler handler = null;

    private DurabilityDegradationRegistry() {}

    /** 注册降级处理器（插件调用） */
    public static void register(IDurabilityDegradationHandler impl) {
        handler = impl;
        LogManager.serverInfo(TAG, "DurabilityDegradationHandler registered: {}", impl.getClass().getName());
    }

    /** 是否可用（耐久插件是否加载） */
    public static boolean isAvailable() {
        return handler != null;
    }

    /** 获取处理器（不可用时返回 null，mixin 放行） */
    public static IDurabilityDegradationHandler get() {
        return handler;
    }
}
