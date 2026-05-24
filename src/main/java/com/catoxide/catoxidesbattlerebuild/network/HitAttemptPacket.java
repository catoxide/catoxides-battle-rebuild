package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record HitAttemptPacket(
        int entityId,
        String boneName,
        float damage,
        long attackTimestamp,
        int entityStateSequence
) implements CustomPacketPayload {
    public static final Type<HitAttemptPacket> TYPE = new Type<>(CatoxidesBattleRebuildConstants.id("hit_attempt"));

    public static final StreamCodec<ByteBuf, HitAttemptPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HitAttemptPacket::entityId,
            ByteBufCodecs.STRING_UTF8, HitAttemptPacket::boneName,
            ByteBufCodecs.FLOAT, HitAttemptPacket::damage,
            ByteBufCodecs.VAR_LONG, HitAttemptPacket::attackTimestamp,
            ByteBufCodecs.VAR_INT, HitAttemptPacket::entityStateSequence,
            HitAttemptPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
