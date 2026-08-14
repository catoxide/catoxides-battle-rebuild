package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.Consumer;

/**
 * 星标切换请求（客户端 → 服务端）。
 * <p>客户端 QuestScreen 点击星标 → 发本包 → 服务端 QuestSystem.setStarred
 * （含 markDirty 持久化）→ 同步包回推刷新客户端缓存。
 */
public record QuestToggleStarPacket(String questId) implements CustomPacketPayload {

    public static final Type<QuestToggleStarPacket> TYPE =
            new Type<>(CatoxidesBattleRebuildConstants.id("quest_toggle_star"));

    public static final StreamCodec<ByteBuf, QuestToggleStarPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, QuestToggleStarPacket::questId,
            QuestToggleStarPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void send(Consumer<CustomPacketPayload> sender) {
        sender.accept(this);
    }
}
