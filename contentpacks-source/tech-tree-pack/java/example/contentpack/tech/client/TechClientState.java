package example.contentpack.tech.client;

import example.contentpack.tech.network.TechSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 客户端缓存：服务端判定的可见/满足状态、已解锁节点、研究项冷却与当前绑定的研究台。 */
public final class TechClientState {

    private static volatile Set<String> unlocked = Set.of();
    private static volatile Set<String> visible = Set.of();
    private static volatile Set<String> met = Set.of();
    private static volatile Map<String, TechSyncPayload.ResearchState> research = Map.of();
    private static volatile boolean synced;
    private static volatile int points;
    private static volatile BlockPos boundPos;
    private static volatile int boundTier = 1;

    private TechClientState() {
    }

    public static void receive(List<String> unlockedIds, int balance, List<String> visibleIds,
                               List<String> metIds, List<TechSyncPayload.ResearchState> researchStates) {
        unlocked = Set.copyOf(unlockedIds);
        points = balance;
        visible = Set.copyOf(visibleIds);
        met = Set.copyOf(metIds);
        research = researchStates.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        TechSyncPayload.ResearchState::id, state -> state));
        synced = true;
    }

    /** 是否已收到过服务端快照（收到前界面按「全部可见、条件未确认」展示）。 */
    public static boolean synced() {
        return synced;
    }

    /** 服务端同步下来的科技点余额（虚拟货币）。 */
    public static int points() {
        return points;
    }

    public static Set<String> unlocked() {
        return unlocked;
    }

    public static boolean isUnlocked(String nodeId) {
        return unlocked.contains(nodeId);
    }

    /** 服务端判定该节点是否作为 UI 元素出现（visibleWhen）。 */
    public static boolean isVisible(String nodeId) {
        return !synced || visible.contains(nodeId);
    }

    /** 服务端判定该节点的解锁条件此刻是否全部满足。 */
    public static boolean isMet(String nodeId) {
        return synced && met.contains(nodeId);
    }

    public static TechSyncPayload.ResearchState researchState(String researchId) {
        return research.get(researchId);
    }

    public static BlockPos boundPos() {
        return boundPos;
    }

    public static int boundTier() {
        return boundTier;
    }

    /** 服务端要求打开界面：绑定研究台并进入（界面渲染读 boundTier）。 */
    public static void open(BlockPos pos, int tier) {
        boundPos = pos;
        boundTier = tier;
        Minecraft.getInstance().setScreen(new TechTreeScreen());
    }

    /** 升级成功后只刷新层级，不重开界面。 */
    public static void updateTable(BlockPos pos, int tier) {
        if (boundPos != null && boundPos.equals(pos)) {
            boundTier = tier;
        }
    }
}
