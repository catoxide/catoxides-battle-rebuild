package com.catoxide.catoxidesbattlerebuild.core.sable;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

/**
 * sub-level 服务注册表
 * <p>由 sable-base contentpack 适配器在 init() 阶段注册 {@link ISubLevelService} 实现。
 * 未注册（sable 未安装 / 适配包未加载）时 {@link #isAvailable()} 返回 false，
 * 调用方优雅降级（如射线追踪退化为纯世界坐标）。
 */
public final class SubLevelServiceRegistry {

    private static final String TAG = "SubLevelServiceRegistry";
    private static volatile ISubLevelService service = null;

    private SubLevelServiceRegistry() {}

    /** 注册服务实现（适配器调用；重复注册覆盖） */
    public static void register(ISubLevelService impl) {
        service = impl;
        LogManager.serverInfo(TAG, "SubLevelService registered: {}", impl.getClass().getName());
    }

    /** 服务是否可用（sable + 适配包是否就绪） */
    public static boolean isAvailable() {
        return service != null;
    }

    /** 获取服务实现（不可用时返回 null，调用方自行降级） */
    public static ISubLevelService get() {
        return service;
    }
}
