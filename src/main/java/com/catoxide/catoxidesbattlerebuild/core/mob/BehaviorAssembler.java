package com.catoxide.catoxidesbattlerebuild.core.mob;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行为组件装配器
 * <p>把实体定义 JSON 中的 {@code behaviors} 配置列表，按 type 通过 {@link IBehaviorFactory}
 * 创建为行为组件实例。内置工厂（attack/ai/raycast）在此注册；
 * 插件可通过 {@link #registerFactory(IBehaviorFactory)} 注册自定义行为类型（扩展点）。
 */
public final class BehaviorAssembler {

    private static final String TAG = "BehaviorAssembler";

    private static final Map<String, IBehaviorFactory> FACTORIES = new HashMap<>();
    private static boolean builtinsRegistered = false;

    private BehaviorAssembler() {}

    /** 注册自定义行为工厂（插件扩展点，需在实体装配前调用） */
    public static synchronized void registerFactory(IBehaviorFactory factory) {
        ensureBuiltins();
        FACTORIES.put(factory.type(), factory);
        LogManager.serverInfo(TAG, "Registered behavior factory: %s", factory.type());
    }

    /** 装配行为组件列表 */
    public static List<IMobBehavior> assemble(List<BehaviorSpec> specs) {
        ensureBuiltins();
        List<IMobBehavior> result = new ArrayList<>();
        if (specs == null) {
            return result;
        }
        for (BehaviorSpec spec : specs) {
            IBehaviorFactory factory = FACTORIES.get(spec.type());
            if (factory == null) {
                LogManager.serverWarn(TAG, "Unknown behavior type '%s' - skipped", spec.type());
                continue;
            }
            try {
                result.add(factory.create(spec.config()));
            } catch (Exception e) {
                LogManager.serverError(TAG, "Failed to create behavior '%s': %s",
                        spec.type(), e.getMessage(), e);
            }
        }
        return result;
    }

    private static void ensureBuiltins() {
        if (builtinsRegistered) {
            return;
        }
        // 先标记再注册：registerFactory 内部会调用 ensureBuiltins，必须先置位避免无限递归
        builtinsRegistered = true;
        registerFactory(new AttackBehavior.Factory());
        registerFactory(new AiBehavior.Factory());
        registerFactory(new RaycastBehavior.Factory());
    }
}
