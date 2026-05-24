package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SyncBodyPartConfigPacket(
        int entityId
) implements CustomPacketPayload {
    public static final Type<SyncBodyPartConfigPacket> TYPE = new Type<>(CatoxidesBattleRebuildConstants.id("sync_body_part_config"));

    public static final StreamCodec<ByteBuf, SyncBodyPartConfigPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncBodyPartConfigPacket::entityId,
            SyncBodyPartConfigPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
