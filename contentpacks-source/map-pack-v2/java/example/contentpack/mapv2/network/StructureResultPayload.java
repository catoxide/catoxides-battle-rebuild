package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import example.contentpack.mapv2.structure.StructureEntry;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：一个查询单元的候选结构点。
 *
 * <p>响应回显 {@code dimension / cellX / cellZ}，客户端据此把结果放进缓存单元——
 * 不需要额外的请求 id，也不会把旧维度的迟到结果写进新世界。
 * {@code truncated=true} 表示服务端因预算或上限提前返回，客户端仍按已到的条目缓存，避免反复重问。
 */
public record StructureResultPayload(ResourceLocation dimension, int cellX, int cellZ,
                                     List<StructureEntry> entries, boolean truncated)
        implements CustomPacketPayload {

    public static final Type<StructureResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "structure_result"));

    public static final StreamCodec<ByteBuf, StructureResultPayload> CODEC = new StreamCodec<>() {
        @Override
        public StructureResultPayload decode(ByteBuf buf) {
            ResourceLocation dimension = ResourceLocation.parse(
                    ByteBufCodecs.STRING_UTF8.decode(buf));
            int cellX = ByteBufCodecs.VAR_INT.decode(buf);
            int cellZ = ByteBufCodecs.VAR_INT.decode(buf);
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            List<StructureEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String setId = ByteBufCodecs.STRING_UTF8.decode(buf);
                int chunkX = ByteBufCodecs.VAR_INT.decode(buf);
                int chunkZ = ByteBufCodecs.VAR_INT.decode(buf);
                byte tier = buf.readByte();
                entries.add(new StructureEntry(setId, chunkX, chunkZ, tier));
            }
            boolean truncated = buf.readBoolean();
            return new StructureResultPayload(dimension, cellX, cellZ, List.copyOf(entries), truncated);
        }

        @Override
        public void encode(ByteBuf buf, StructureResultPayload payload) {
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.dimension().toString());
            ByteBufCodecs.VAR_INT.encode(buf, payload.cellX());
            ByteBufCodecs.VAR_INT.encode(buf, payload.cellZ());
            ByteBufCodecs.VAR_INT.encode(buf, payload.entries().size());
            for (StructureEntry entry : payload.entries()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, entry.setId());
                ByteBufCodecs.VAR_INT.encode(buf, entry.chunkX());
                ByteBufCodecs.VAR_INT.encode(buf, entry.chunkZ());
                buf.writeByte(entry.tier());
            }
            buf.writeBoolean(payload.truncated());
        }
    };

    @Override
    public Type<StructureResultPayload> type() {
        return TYPE;
    }
}
