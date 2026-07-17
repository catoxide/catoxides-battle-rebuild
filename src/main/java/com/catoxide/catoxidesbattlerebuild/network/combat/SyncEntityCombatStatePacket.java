package com.catoxide.catoxidesbattlerebuild.network.combat;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public record SyncEntityCombatStatePacket(
        int entityId,
        Map<String, Vector3f> bonePositions,
        Map<String, Float> boneHealths,
        List<String> destroyedBones
) implements CustomPacketPayload {
    public static final Type<SyncEntityCombatStatePacket> TYPE = new Type<>(
            CatoxidesBattleRebuildConstants.id("sync_entity_combat_state"));

    public static final StreamCodec<ByteBuf, Vector3f> VECTOR3F_STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, Vector3f::x,
            ByteBufCodecs.FLOAT, Vector3f::y,
            ByteBufCodecs.FLOAT, Vector3f::z,
            Vector3f::new
    );

    public static final StreamCodec<ByteBuf, SyncEntityCombatStatePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncEntityCombatStatePacket::entityId,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, VECTOR3F_STREAM_CODEC),
            SyncEntityCombatStatePacket::bonePositions,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.FLOAT),
            SyncEntityCombatStatePacket::boneHealths,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8),
            SyncEntityCombatStatePacket::destroyedBones,
            SyncEntityCombatStatePacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void send(Consumer<CustomPacketPayload> sender) {
        sender.accept(this);
    }
}
