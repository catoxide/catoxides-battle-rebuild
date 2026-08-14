package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务状态同步包（服务端 → 客户端）。
 * <p>平铺的任务树节点列表（parentId 关联父子，避免递归 codec）：
 * 根节点 parentId 为 null，子节点 parentId = 父节点 questId。
 * <p>发送时机：服务端定时（每 20 tick）对每个在线玩家推送完整任务树。
 */
public record QuestSyncPacket(List<QuestEntry> entries) implements CustomPacketPayload {

    public static final Type<QuestSyncPacket> TYPE =
            new Type<>(CatoxidesBattleRebuildConstants.id("quest_sync"));

    /** 平铺任务节点（客户端重建任务树 + HUD 显示）。
     * <p>instanceId = 唯一实例 ID（同定义可多实例，靠它区分）；
     * parentInstanceId = 父实例 ID（空串 = 根）；definitionId = 定义 id（显示/匹配用）。 */
    public record QuestEntry(
            String instanceId,
            String definitionId,
            String title,
            String description,
            String state,
            boolean starred,
            String parentInstanceId
    ) {
    }

    /** 手写 codec：7 字段超出 StreamCodec.composite 的 6 参数上限 */
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
                    ByteBufCodecs.STRING_UTF8.decode(buf));
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
