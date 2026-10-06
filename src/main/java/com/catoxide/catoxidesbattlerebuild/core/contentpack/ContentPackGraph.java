package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ContentPack 依赖图 + 隔离状态机（KubeJS 式故障隔离）。
 *
 * <h3>它解决什么</h3>
 * 旧行为：包 B 在 {@code register()} 里抛异常 → 日志里一行错误，然后 <b>依赖 B 的包 A 照跑不误</b>。
 * A 拿不到 B 的能力（返回 empty），于是静默降级：A 注册的内容半死不活地留在注册表里，
 * 玩家看到的是「东西在，但不工作」。更糟的是旧代码只 {@code catch (Exception)}，
 * 抓不到 {@code NoClassDefFoundError} —— 包少带一个类就直接把整个游戏带走。
 *
 * <h3>现在的规则</h3>
 * <ol>
 *   <li>一个包自己出错 → 它 {@link Status#FAILED}</li>
 *   <li>沿 <b>硬依赖</b> 的反向边做传递闭包：所有直接/间接需要它的包一并 {@link Status#DISABLED}
 *       <ul><li>A→B→C，C 失败 ⇒ C 失败，B、A 被取消加载</li></ul></li>
 *   <li><b>软依赖不传播</b>（{@code Module-Dependencies-Optional} 的全部意义就是「它不在我也能活」）</li>
 *   <li>游戏继续跑，不阻止启动</li>
 * </ol>
 *
 * <h3>为什么叫「取消加载」而不是「回滚」</h3>
 * NeoForge 的 {@code DeferredRegister} 没有反注册 API，{@code ModConfig} 与 {@code modEventBus}
 * 监听器同样是单向的。所以隔离的语义是 <b>切断消费路径</b>，不是抹掉痕迹：
 * 被隔离包留下的 EntityType/Config 仍然存在，但没有任何代码认领它们（惰性残留）。
 * {@link CapabilityRegistry} 是唯一能真正回收的（{@link CapabilityRegistry#revokeAllFrom}）。
 * 被隔离包的 {@code data/} 也不再挂载（{@link ContentPackRepositorySource}），
 * 于是它写坏的注册表 JSON 不会把世界带崩。
 *
 * <h3>顺序保证</h3>
 * 加载器按拓扑序注册包，因此失败包的依赖者<b>必然还没跑</b>，隔离总能赶在它们之前生效。
 * 唯一的例外是循环依赖（拓扑序退化为尽力排序）——那时 {@link Fallout#wasActive()}
 * 会告诉调用方「这个包已经 register 过了，隔离来晚了」。
 *
 * <h3>纯粹性</h3>
 * 本类不依赖 Minecraft、不写日志，只维护状态并返回结果，因此可以脱离游戏单独测试
 * （见 {@code .scratch/pack-graph-check}）。
 */
public final class ContentPackGraph {

    private ContentPackGraph() {}

    /** 包的生命周期状态。 */
    public enum Status {
        /** 已发现，尚未执行 register()。 */
        PENDING,
        /** register() 已成功返回。 */
        ACTIVE,
        /** 自身出错（扫描/加载/register/init）。 */
        FAILED,
        /** 自身没问题，但硬依赖链上有包失败 ⇒ 被连带取消加载。 */
        DISABLED
    }

    /**
     * 连带取消的结果。
     *
     * @param id        被取消的包
     * @param wasActive 取消时它是否已经跑过 register()（true = 隔离来晚了，只在循环依赖下出现）
     */
    public record Fallout(String id, boolean wasActive) {}

    /** 只读快照（日志/诊断用）。 */
    public record Snapshot(String id, Status status, String phase, String reason, List<String> hardDeps) {
        public boolean isRoot() {
            return status == Status.FAILED;
        }
    }

    /** 可变节点。 */
    private static final class Node {
        final String id;
        final Set<String> hardDeps = new LinkedHashSet<>();
        final Set<String> softDeps = new LinkedHashSet<>();
        Status status = Status.PENDING;
        String phase = "-";
        String reason = null;

        Node(String id) {
            this.id = id;
        }
    }

    /** id → 节点。插入顺序 = 发现顺序（与 ContentPackRegistry 一致，便于日志比对）。 */
    private static final Map<String, Node> NODES = new LinkedHashMap<>();

    /** id → 直接依赖它的包（硬依赖反向边）。 */
    private static final Map<String, Set<String>> DEPENDENTS = new LinkedHashMap<>();

    /** 清空（仅测试/热重载用）。 */
    public static synchronized void reset() {
        NODES.clear();
        DEPENDENTS.clear();
    }

    /**
     * 登记一个包及其依赖边。可重复调用（幂等）。
     *
     * @param id       包 id（= MANIFEST 的 {@code Module-Name}，同时是依赖图的键）
     * @param hardDeps {@code Module-Dependencies}：缺失即不可用
     * @param softDeps {@code Module-Dependencies-Optional}：仅用于诊断，不参与隔离传播
     */
    public static synchronized void register(String id, Collection<String> hardDeps, Collection<String> softDeps) {
        if (id == null || id.isBlank()) {
            return;
        }
        Node node = NODES.computeIfAbsent(id, Node::new);
        addEdges(node.hardDeps, hardDeps, id);
        addEdges(node.softDeps, softDeps, id);
        for (String dep : node.hardDeps) {
            DEPENDENTS.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(id);
        }
    }

    /** 自环与空名不构成有效的依赖边（自环只会让包把自己拖下水）。 */
    private static void addEdges(Set<String> target, Collection<String> source, String selfId) {
        if (source == null) {
            return;
        }
        for (String dep : source) {
            if (dep != null && !dep.isBlank() && !dep.equals(selfId)) {
                target.add(dep);
            }
        }
    }

    /**
     * 标记一个包失败，并沿硬依赖反向边传递闭包，把依赖它的包全部取消加载。
     *
     * <p>幂等：已经 FAILED 的包再次调用返回空列表（不重复报警）。
     *
     * @param id     失败的包
     * @param phase  出错阶段（{@code manifest}/{@code load}/{@code register}/{@code init}）
     * @param reason 人类可读原因
     * @return 被连带取消的包（不含 id 自身）；{@code wasActive()} 标记隔离来晚了的例外情况
     */
    public static synchronized List<Fallout> fail(String id, String phase, String reason) {
        Node root = NODES.get(id);
        if (root == null) {
            // 没登记过（例如 manifest 都读不出来的 jar）——没有依赖边可传播，仅记录日志由调用方负责
            return List.of();
        }
        if (root.status == Status.FAILED) {
            return List.of();
        }
        root.status = Status.FAILED;
        root.phase = phase;
        root.reason = reason;

        List<Fallout> fallout = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> queue = new LinkedHashSet<>();
        queue.add(id);
        visited.add(id);

        while (!queue.isEmpty()) {
            String current = queue.iterator().next();
            queue.remove(current);
            Node current2 = NODES.get(current);
            String cause = current2 == null ? "unavailable"
                    : current2.status == Status.FAILED ? "failed (" + current2.reason + ")"
                    : "was cancelled";

            for (String dependent : DEPENDENTS.getOrDefault(current, Set.of())) {
                if (!visited.add(dependent)) {
                    continue;
                }
                Node node = NODES.get(dependent);
                if (node == null) {
                    continue;
                }
                boolean wasActive = node.status == Status.ACTIVE;
                node.status = Status.DISABLED;
                node.phase = "dependency";
                node.reason = "required pack '" + current + "' " + cause;
                fallout.add(new Fallout(dependent, wasActive));
                queue.add(dependent);
            }
        }
        return fallout;
    }

    /** register() 成功返回 → 标记为 ACTIVE。已失败/已取消的包不会被复活。 */
    public static synchronized void activate(String id) {
        Node node = NODES.get(id);
        if (node != null && node.status == Status.PENDING) {
            node.status = Status.ACTIVE;
            node.phase = "-";
        }
    }

    /**
     * 该包是否被隔离（FAILED 或 DISABLED）。
     * <p>未登记的 id 返回 {@code false} —— 认不出来的东西不该被当成坏的，
     * 否则一处笔误会静默关掉全部内容。
     */
    public static synchronized boolean isQuarantined(String id) {
        Node node = id == null ? null : NODES.get(id);
        return node != null && (node.status == Status.FAILED || node.status == Status.DISABLED);
    }

    /** 该包是否可以执行（PENDING = 还没跑，ACTIVE = 已成功）。 */
    public static synchronized boolean isRunnable(String id) {
        Node node = id == null ? null : NODES.get(id);
        // 未登记 → 放行（与 isQuarantined 的宽容策略一致）
        return node == null || node.status == Status.PENDING || node.status == Status.ACTIVE;
    }

    public static synchronized Status statusOf(String id) {
        Node node = id == null ? null : NODES.get(id);
        return node == null ? null : node.status;
    }

    /** 隔离原因；未被隔离则返回 null。 */
    public static synchronized String reasonOf(String id) {
        Node node = id == null ? null : NODES.get(id);
        return node == null ? null : node.reason;
    }

    /** 出错阶段；正常时为 "-"。 */
    public static synchronized String phaseOf(String id) {
        Node node = id == null ? null : NODES.get(id);
        return node == null ? null : node.phase;
    }

    /** 直接依赖该包的包（诊断用）。 */
    public static synchronized List<String> directDependents(String id) {
        Set<String> set = id == null ? null : DEPENDENTS.get(id);
        return set == null ? List.of() : List.copyOf(set);
    }

    /** 全部被隔离的包（FAILED 在前、DISABLED 在后，各自保持发现顺序）。 */
    public static synchronized List<Snapshot> quarantined() {
        List<Snapshot> roots = new ArrayList<>();
        List<Snapshot> cascaded = new ArrayList<>();
        for (Node node : NODES.values()) {
            if (node.status == Status.FAILED) {
                roots.add(snapshot(node));
            } else if (node.status == Status.DISABLED) {
                cascaded.add(snapshot(node));
            }
        }
        roots.addAll(cascaded);
        return roots;
    }

    private static Snapshot snapshot(Node node) {
        return new Snapshot(node.id, node.status, node.phase, node.reason, List.copyOf(node.hardDeps));
    }

    public static synchronized boolean hasFailures() {
        for (Node node : NODES.values()) {
            if (node.status == Status.FAILED || node.status == Status.DISABLED) {
                return true;
            }
        }
        return false;
    }

    public static synchronized int failedCount() {
        int count = 0;
        for (Node node : NODES.values()) {
            if (node.status == Status.FAILED) {
                count++;
            }
        }
        return count;
    }

    public static synchronized int disabledCount() {
        int count = 0;
        for (Node node : NODES.values()) {
            if (node.status == Status.DISABLED) {
                count++;
            }
        }
        return count;
    }

    public static synchronized int size() {
        return NODES.size();
    }
}
