package com.catoxide.catoxidesbattlerebuild.network.combat;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record BodyDestructionSyncPacket(
        int entityId,
        List<String> destroyedBones
) implements CustomPacketPayload {
    public static final Type<BodyDestructionSyncPacket> TYPE = new Type<>(
            CatoxidesBattleRebuildConstants.id("body_destruction_sync")
    );

    public static final StreamCodec<ByteBuf, BodyDestructionSyncPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BodyDestructionSyncPacket::entityId,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), BodyDestructionSyncPacket::destroyedBones,
            BodyDestructionSyncPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
