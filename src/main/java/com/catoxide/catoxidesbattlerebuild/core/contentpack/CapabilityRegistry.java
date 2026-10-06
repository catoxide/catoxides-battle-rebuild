package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 跨内容包能力注册表 —— {@link Capability} 的提供者/消费者汇合点。
 *
 * <h3>它替代了什么</h3>
 * 旧机制是 {@code Module-Dependencies} 把依赖包的 jar 注入到使用者的类加载闭包中
 * （「共享类空间」）。那条路子是错的：依赖包的类在每个使用者那里各有一份副本，
 * 静态状态静默发散。本注册表改用 <b>接口在 core、实现在包内</b> 的依赖倒置，
 * 使包间能力传递不依赖任何类空间共享。
 *
 * <h3>时序</h3>
 * <pre>
 * executeAllRegistries():
 *   ① 按拓扑序对每个包调用 register()   ← provide() 在此阶段可用；后面的包能看到前面包提供的能力
 *   ② ContentPackRegistry.lock() / CapabilityRegistry.lock() / BehaviorRouter.lock()
 *   ③ 对所有包调用 init()                ← 只能读（get/has）
 * </pre>
 *
 * <p>正因为 ① 按拓扑序执行，包 A 只要在 MANIFEST 中声明依赖包 B，
 * 就一定能在自己的 {@code register()} 里拿到 B 的能力。
 * 若没有声明依赖，B 可能排在 A 之后，A 拿不到能力（拿不到是安全失败，返回 empty）。
 *
 * <h3>线程模型</h3>
 * 注册发生在 mod 加载线程，单线程顺序执行；所有方法 synchronized 仅作防护，
 * 不引入额外竞争。
 */
public final class CapabilityRegistry {

    private static final String TAG = "CapabilityRegistry";

    /** 保序表：id → 条目。顺序 = 提供顺序（= 拓扑序），便于诊断。 */
    private static final Map<Capability<?>, Entry<?>> PROVIDED = new LinkedHashMap<>();

    private static volatile boolean locked = false;

    private CapabilityRegistry() {}

    /** 内部条目：token + 实现 + 提供者包 id（诊断用）。 */
    private record Entry<T>(Capability<T> capability, T impl, String provider) {}

    /**
     * 提供一个能力实现。
     *
     * @param capability token（必须与消费者持有的是同一个逻辑 token，即同 id 同类型）
     * @param impl       实现实例；其 Class 来自提供者包的 ClassLoader
     * @param provider   提供者包 id（供诊断；由 {@link ContentPackContext} 自动填充）
     */
    public static synchronized <T> void provide(Capability<T> capability, T impl, String provider) {
        if (capability == null) {
            LogManager.serverError(TAG, "Ignoring provide() with null capability (provider: %s)", provider);
            return;
        }
        if (impl == null) {
            LogManager.serverError(TAG, "Ignoring provide() of '%s' with null implementation (provider: %s)",
                    capability.id(), provider);
            return;
        }
        if (locked) {
            LogManager.serverError(TAG, "Cannot provide '%s' - registry is locked (provider: %s)",
                    capability.id(), provider);
            return;
        }

        // 类型必须先于重复检查校验：同名不同型是「定义冲突」，不是「重复提供」。
        if (!capability.type().isInstance(impl)) {
            LogManager.serverError(TAG,
                    "Capability '%s' declared as %s but provider '%s' supplied %s (not an instance) - ignored",
                    capability.id(), capability.type().getName(), provider, impl.getClass().getName());
            return;
        }

        Entry<?> existing = PROVIDED.get(capability);
        if (existing != null) {
            if (!capability.type().equals(existing.capability().type())) {
                LogManager.serverError(TAG,
                        "Capability '%s' type conflict: '%s' declared it as %s, but it was already provided as %s - ignored",
                        capability.id(), provider, capability.type().getName(),
                        existing.capability().type().getName());
            } else {
                LogManager.serverError(TAG,
                        "Capability '%s' already provided by pack '%s'; duplicate from pack '%s' ignored (first provider wins)",
                        capability.id(), existing.provider(), provider);
            }
            return;
        }

        PROVIDED.put(capability, new Entry<>(capability, impl, provider));
        LogManager.serverInfo(TAG, "  capability '%s' <- pack '%s' (%s)",
                capability.id(), provider, impl.getClass().getName());
    }

    /**
     * 回收某个包提供的全部能力 —— 故障隔离的配套动作。
     *
     * <p>包 B 在 {@code register()} 中途失败时，可能已经 {@code provide()} 了一部分能力。
     * 若不回收，依赖 B 的包 A 会拿到一个<b>半初始化</b>的实现并当成好的用 ——
     * 这类跨包静默发散是最难查的 bug（远端 NPE，根因在另一个包）。
     *
     * <p>只能在 {@link #lock()} 之前调用（register 阶段）。锁定后依赖者已消费完毕，
     * 此时回收只会制造不一致，因此拒绝并报错。
     *
     * @param provider 提供者包 id
     * @return 被回收的能力 id 列表
     */
    public static synchronized List<String> revokeAllFrom(String provider) {
        if (provider == null) {
            return List.of();
        }
        if (locked) {
            LogManager.serverError(TAG,
                    "Cannot revoke capabilities of failed pack '%s' - registry already locked (too late)", provider);
            return List.of();
        }
        List<String> revoked = new ArrayList<>();
        var iterator = PROVIDED.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (provider.equals(entry.getValue().provider())) {
                revoked.add(entry.getKey().id().toString());
                iterator.remove();
            }
        }
        if (!revoked.isEmpty()) {
            LogManager.serverWarn(TAG, "  revoked %d capability(ies) of failed pack '%s': %s",
                    revoked.size(), provider, String.join(", ", revoked));
        }
        return revoked;
    }

    /**
     * 获取能力实现。
     * <p>返回 {@link Optional#empty()} 表示「无人提供」，这是<b>正常的、可预期的</b>结果
     * —— 包 A 依赖可选包 B 时就靠这个分支决定是否启用兼容内容。
     */
    @SuppressWarnings("unchecked")
    public static synchronized <T> Optional<T> get(Capability<T> capability) {
        if (capability == null) {
            return Optional.empty();
        }
        Entry<?> entry = PROVIDED.get(capability);
        if (entry == null) {
            return Optional.empty();
        }
        if (!capability.type().isInstance(entry.impl())) {
            // 走到这里说明有包用不同 Class 定义了同名 token —— 几乎总是「接口没放在 core」。
            LogManager.serverError(TAG,
                    "Capability '%s' instance is %s but requester expects %s - refusing to cast (is the interface declared in core?)",
                    capability.id(), entry.impl().getClass().getName(), capability.type().getName());
            return Optional.empty();
        }
        return Optional.of((T) entry.impl());
    }

    /** 该能力是否已被提供（不关心类型是否匹配调用方预期）。 */
    public static synchronized boolean has(Capability<?> capability) {
        return capability != null && PROVIDED.containsKey(capability);
    }

    /** 提供该能力的包 id（诊断用）。 */
    public static synchronized Optional<String> providerOf(Capability<?> capability) {
        Entry<?> entry = capability == null ? null : PROVIDED.get(capability);
        return entry == null ? Optional.empty() : Optional.of(entry.provider());
    }

    /** 已提供的能力总数。 */
    public static synchronized int size() {
        return PROVIDED.size();
    }

    /**
     * 锁定注册表：全部 {@code register()} 完成后调用，禁止后续新增提供。
     * <p>读取（{@link #get}/{@link #has}）不受影响。
     */
    public static synchronized void lock() {
        locked = true;
        if (PROVIDED.isEmpty()) {
            LogManager.serverInfo(TAG, "Registry locked. No cross-pack capability was provided.");
        } else {
            LogManager.serverInfo(TAG, "Registry locked. %d cross-pack capability(ies) available:", PROVIDED.size());
            for (Entry<?> entry : PROVIDED.values()) {
                LogManager.serverInfo(TAG, "  - %s <- pack '%s'", entry.capability().id(), entry.provider());
            }
        }
    }

    public static synchronized boolean isLocked() {
        return locked;
    }

    /** 已提供能力的可读清单（诊断/命令输出用）。 */
    public static synchronized List<String> describeProvided() {
        List<String> result = new ArrayList<>(PROVIDED.size());
        for (Entry<?> entry : PROVIDED.values()) {
            result.add(entry.capability().id() + " <- " + entry.provider()
                    + " (" + entry.impl().getClass().getName() + ")");
        }
        return result;
    }
}
