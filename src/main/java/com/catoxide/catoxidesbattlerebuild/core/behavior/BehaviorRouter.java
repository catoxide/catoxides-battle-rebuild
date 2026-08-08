package com.catoxide.catoxidesbattlerebuild.core.behavior;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 行为分发器（BehaviorRouter）
 * <p>插件体系的行为路由核心：主 mod 的行为入口（如 {@code DamageProcessor.processHit}）
 * 调用本类的方法，本类查询插件注册的处理器：
 * <ul>
 *   <li>有插件处理器匹配（{@code handles == true}）且返回已接管 → 插件逻辑生效，短路默认逻辑</li>
 *   <li>无处理器 / 处理器放弃 → 走主 mod 默认逻辑</li>
 * </ul>
 *
 * <p><b>与 mixin 的关系</b>：本类是「mixin 注入点 → 行为分发」架构中的行为分发层。
 * mixin 在主 mod（静态壳），行为处理器在插件（动态插拔），本类负责两者之间的路由。
 */
public final class BehaviorRouter {

    private static final String TAG = "BehaviorRouter";

    /** 受击处理器注册表（按注册顺序） */
    private static final List<IHurtHandler> HURT_HANDLERS = new CopyOnWriteArrayList<>();

    /** 注册锁定：内容包加载阶段结束后禁止再注册 */
    private static volatile boolean locked = false;

    private BehaviorRouter() {}

    /**
     * 注册受击处理器（插件在其 init() 阶段调用）。
     */
    public static void registerHurtHandler(IHurtHandler handler) {
        if (locked) {
            LogManager.serverWarn(TAG, "Cannot register hurt handler - router locked");
            return;
        }
        HURT_HANDLERS.add(handler);
        LogManager.serverInfo(TAG, "Registered hurt handler: %s", handler.getClass().getSimpleName());
    }

    /**
     * 锁定路由（内容包注册阶段结束后调用，禁止后续注册）。
     */
    public static void lock() {
        locked = true;
        LogManager.serverInfo(TAG, "BehaviorRouter locked. %d hurt handler(s) registered.", HURT_HANDLERS.size());
    }

    public static boolean isLocked() {
        return locked;
    }

    /**
     * 路由受击事件：遍历插件处理器，第一个匹配并接管者胜出。
     *
     * @return true = 已被插件接管（调用方应短路默认逻辑）
     */
    public static boolean routeHurt(HurtContext ctx) {
        for (IHurtHandler handler : HURT_HANDLERS) {
            try {
                if (handler.handles(ctx.target()) && handler.handleHurt(ctx)) {
                    LogManager.serverDebug(TAG, "Hurt handled by %s for entity {}",
                            handler.getClass().getSimpleName(), ctx.target().getId());
                    return true;
                }
            } catch (Exception e) {
                LogManager.serverError(TAG, "Hurt handler '%s' threw: %s",
                        handler.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
        return false;
    }

    /** 当前已注册的受击处理器数量（诊断用） */
    public static int hurtHandlerCount() {
        return HURT_HANDLERS.size();
    }
}
