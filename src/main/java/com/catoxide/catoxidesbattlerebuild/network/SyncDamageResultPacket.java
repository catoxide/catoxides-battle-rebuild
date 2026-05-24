package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SyncDamageResultPacket(
        int entityId,
        String boneName,
        String partName,
        float damage,
        boolean isFatal
) implements CustomPacketPayload {
    public static final Type<SyncDamageResultPacket> TYPE = new Type<>(CatoxidesBattleRebuildConstants.id("sync_damage_result"));

    public static final StreamCodec<ByteBuf, SyncDamageResultPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncDamageResultPacket::entityId,
            ByteBufCodecs.STRING_UTF8, SyncDamageResultPacket::boneName,
            ByteBufCodecs.STRING_UTF8, SyncDamageResultPacket::partName,
            ByteBufCodecs.FLOAT, SyncDamageResultPacket::damage,
            ByteBufCodecs.BOOL, SyncDamageResultPacket::isFatal,
            SyncDamageResultPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
