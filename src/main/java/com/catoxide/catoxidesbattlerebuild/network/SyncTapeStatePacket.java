package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * TimeTape 状态机同步（S2C）：实体脏轨的 (轨, 状态, 本地时钟t) + tag 镜像。
 * <p>客户端纯回放：按同一份状态机 JSON 定位当前段动画 → 本地播放（不评条件）。
 * <b>tag 必须同步</b>：alt 动画选择（如 hit_front/hit_back 按 hit_dir 选）依赖 tag，
 * 客户端无 tag 会永远选默认动画。
 * 节流在服务端（状态变化即发，否则 4 tick 一次）。
 */
public record SyncTapeStatePacket(int entityId, List<TrackState> states, Map<String, String> tags) implements CustomPacketPayload {

    /** 单轨同步项 */
    public record TrackState(String track, String state, float t) {
        public static final StreamCodec<ByteBuf, TrackState> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, TrackState::track,
                ByteBufCodecs.STRING_UTF8, TrackState::state,
                ByteBufCodecs.FLOAT, TrackState::t,
                TrackState::new
        );
    }

    public static final Type<SyncTapeStatePacket> TYPE = new Type<>(CatoxidesBattleRebuildConstants.id("sync_tape_state"));

    public static final StreamCodec<ByteBuf, SyncTapeStatePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncTapeStatePacket::entityId,
            ByteBufCodecs.collection(ArrayList::new, TrackState.CODEC), SyncTapeStatePacket::states,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8), SyncTapeStatePacket::tags,
            SyncTapeStatePacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void send(Consumer<CustomPacketPayload> sender) {
        sender.accept(this);
    }
}
