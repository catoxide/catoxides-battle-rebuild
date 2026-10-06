package example.contentpack.questtest.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务状态同步包（服务端 → 客户端）。
 * <p>平铺的任务树节点列表（parentId 关联父子，避免递归 codec）：
 * 根节点 parentId 为 null，子节点 parentId = 父节点 questId。
 * <p>发送时机：服务端定时（每 20 tick）对每个在线玩家推送完整任务树。
 * <p><b>M3 迁移</b>：由 core 移入本包（整条同步管线随包插拔）。
 * payload id 保持 {@code catoxidesbattlerebuild:quest_sync} 不变（B6：命名空间不随包走；
 * 协议稳定性优先于包命名空间独立性）。
 */
public record QuestSyncPacket(List<QuestEntry> entries) implements CustomPacketPayload {

    public static final Type<QuestSyncPacket> TYPE =
            new Type<>(ResourceLocation.parse("catoxidesbattlerebuild:quest_sync"));

    /** 平铺任务节点（客户端重建任务树 + HUD 显示）。
     * <p>instanceId = 唯一实例 ID（同定义可多实例，靠它区分）；
     * parentInstanceId = 父实例 ID（空串 = 根）；definitionId = 定义 id（显示/匹配用）；
     * location = 任务目标位置（"x,y,z" 或空串，UI 显示目标）；
     * progress = 进度展示数据（由各 goal 的 {@code IQuestGoal.getProgress()} 合并，
     * 如 {killed=2.0, need=2.0}——首次把进度通道接通到 UI）。 */
    public record QuestEntry(
            String instanceId,
            String definitionId,
            String title,
            String description,
            String state,
            boolean starred,
            String parentInstanceId,
            String location,
            Map<String, Float> progress
    ) {
    }

    /** 进度 map codec（M 被 IntFunction 固定为 HashMap，故 encode 需包一层） */
    public static final StreamCodec<ByteBuf, HashMap<String, Float>> PROGRESS_CODEC =
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.FLOAT);

    /** 手写 codec：9 字段超出 StreamCodec.composite 的 6 参数上限 */
    public static final StreamCodec<ByteBuf, QuestEntry> ENTRY_CODEC = new StreamCodec<>() {
        @Override
        public QuestEntry decode(ByteBuf buf) {
            return new QuestEntry(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    PROGRESS_CODEC.decode(buf));
        }

        @Override
        public void encode(ByteBuf buf, QuestEntry entry) {
            ByteBufCodecs.STRING_UTF8.encode(buf, entry.instanceId());
            ByteBufCodecs.STRING_UTF8.encode(buf, entry.definitionId());
            ByteBufCodecs.STRING_UTF8.encode(buf, entry.title());
            ByteBufCodecs.STRING_UTF8.encode(buf, entry.description());
            ByteBufCodecs.STRING_UTF8.encode(buf, entry.state());
            ByteBufCodecs.BOOL.encode(buf, entry.starred());
            ByteBufCodecs.STRING_UTF8.encode(buf, entry.parentInstanceId());
            ByteBufCodecs.STRING_UTF8.encode(buf, entry.location());
            PROGRESS_CODEC.encode(buf, new HashMap<>(entry.progress()));
        }
    };

    public static final StreamCodec<ByteBuf, QuestSyncPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, ENTRY_CODEC),
            QuestSyncPacket::entries,
            QuestSyncPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Send this packet to a player using their connection
     */
    public void send(java.util.function.Consumer<CustomPacketPayload> sender) {
        sender.accept(this);
    }
}
