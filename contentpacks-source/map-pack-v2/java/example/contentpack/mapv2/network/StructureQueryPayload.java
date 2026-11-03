package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：请求一个结构查询单元内的候选点。
 *
 * <p>只发维度 + 单元坐标，不发 seed、不发注册表（D92：计算严格在服务端）。
 * 单元边长是协议常量 {@code StructureScan.CELL_CHUNKS}，两端共用，避免请求/响应尺寸对不上。
 */
public record StructureQueryPayload(ResourceLocation dimension, int cellX, int cellZ)
        implements CustomPacketPayload {

    public static final Type<StructureQueryPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "structure_query"));

    public static final StreamCodec<ByteBuf, StructureQueryPayload> CODEC = new StreamCodec<>() {
        @Override
        public StructureQueryPayload decode(ByteBuf buf) {
            ResourceLocation dimension = ResourceLocation.parse(
                    ByteBufCodecs.STRING_UTF8.decode(buf));
            int cellX = ByteBufCodecs.VAR_INT.decode(buf);
            int cellZ = ByteBufCodecs.VAR_INT.decode(buf);
            return new StructureQueryPayload(dimension, cellX, cellZ);
        }

        @Override
        public void encode(ByteBuf buf, StructureQueryPayload payload) {
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.dimension().toString());
            ByteBufCodecs.VAR_INT.encode(buf, payload.cellX());
            ByteBufCodecs.VAR_INT.encode(buf, payload.cellZ());
        }
    };

    @Override
    public Type<StructureQueryPayload> type() {
        return TYPE;
    }
}
