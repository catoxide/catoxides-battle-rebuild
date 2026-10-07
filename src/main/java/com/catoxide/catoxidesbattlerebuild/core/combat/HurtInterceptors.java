package com.catoxide.catoxidesbattlerebuild.core.combat;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 受击拦截器注册表（仿 {@code DurabilityDegradationRegistry}，多槽位以容纳多个战斗包）。
 * <p>M5 阶段由主类单一注册点登记 core 实现（F4）；M6 起登记动作移入战斗内容包的
 * {@code register()}，core 侧不再持有任何战斗实现。
 * <p>R3：为空时受击 mixin 门全部放行，行为 = 原版。
 */
public final class HurtInterceptors {

    private static final String TAG = "HurtInterceptors";
    private static final List<IHurtInterceptor> INTERCEPTORS = new CopyOnWriteArrayList<>();

    private HurtInterceptors() {}

    /** 登记拦截器（注册阶段调用，按调用顺序生效） */
    public static void register(IHurtInterceptor interceptor) {
        INTERCEPTORS.add(interceptor);
        LogManager.serverInfo(TAG, "HurtInterceptor registered: {}", interceptor.getClass().getName());
    }

    /** 是否有拦截器可用（战斗内容是否加载） */
    public static boolean isAvailable() {
        return !INTERCEPTORS.isEmpty();
    }

    /** 全部拦截器（注册顺序）；门按序遍历，第一个接管的生效 */
    public static List<IHurtInterceptor> getAll() {
        return INTERCEPTORS;
    }
}
