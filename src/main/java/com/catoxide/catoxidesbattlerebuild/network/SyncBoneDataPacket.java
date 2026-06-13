package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public record SyncBoneDataPacket(int entityId, Map<String, Vector3f> bonePositions) implements CustomPacketPayload {
    
    public static final Type<SyncBoneDataPacket> TYPE = new Type<>(CatoxidesBattleRebuildConstants.id("sync_bone_data"));

    public static final StreamCodec<ByteBuf, SyncBoneDataPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncBoneDataPacket::entityId,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, 
                StreamCodec.composite(
                    ByteBufCodecs.FLOAT, (v) -> v.x,
                    ByteBufCodecs.FLOAT, (v) -> v.y,
                    ByteBufCodecs.FLOAT, (v) -> v.z,
                    Vector3f::new
                )), SyncBoneDataPacket::bonePositions,
            SyncBoneDataPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
    
    /**
     * Send this packet to a player using their connection
     */
    public void send(Consumer<CustomPacketPayload> sender) {
        sender.accept(this);
    }
}