package com.catoxide.catoxidesbattlerebuild.core.durability;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

/**
 * 耐久服务注册表
 * <p>由 durability contentpack 插件在 init() 阶段注册 {@link IDurabilityService} 实现。
 * 未注册（插件未加载）时 {@link #isAvailable()} 返回 false，主 mod mixin 回退原版行为。
 */
public final class DurabilityServiceRegistry {

    private static final String TAG = "DurabilityServiceRegistry";
    private static volatile IDurabilityService service = null;

    private DurabilityServiceRegistry() {}

    /** 注册服务实现（插件调用；重复注册覆盖） */
    public static void register(IDurabilityService impl) {
        service = impl;
        LogManager.serverInfo(TAG, "DurabilityService registered: {}", impl.getClass().getName());
    }

    /** 服务是否可用（耐久插件是否加载） */
    public static boolean isAvailable() {
        return service != null;
    }

    /** 获取服务实现（不可用时返回 null，调用方回退原版） */
    public static IDurabilityService get() {
        return service;
    }
}
