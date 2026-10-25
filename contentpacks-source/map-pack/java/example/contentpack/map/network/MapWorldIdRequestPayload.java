package example.contentpack.map.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：登录后请求当前世界的身份 ID。 */
public record MapWorldIdRequestPayload() implements CustomPacketPayload {

    public static final Type<MapWorldIdRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "map_world_id_request"));

    public static final StreamCodec<ByteBuf, MapWorldIdRequestPayload> CODEC =
            StreamCodec.unit(new MapWorldIdRequestPayload());

    @Override
    public Type<MapWorldIdRequestPayload> type() {
        return TYPE;
    }
}
