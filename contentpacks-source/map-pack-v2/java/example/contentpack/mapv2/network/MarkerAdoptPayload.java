package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** 客户端 → 服务端：把别人分享的永久地点标记复制成自己的（永久化）。 */
public record MarkerAdoptPayload(UUID id) implements CustomPacketPayload {

    public static final Type<MarkerAdoptPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "marker_adopt"));

    public static final StreamCodec<ByteBuf, MarkerAdoptPayload> CODEC = new StreamCodec<>() {
        @Override
        public MarkerAdoptPayload decode(ByteBuf buf) {
            return new MarkerAdoptPayload(new UUID(buf.readLong(), buf.readLong()));
        }

        @Override
        public void encode(ByteBuf buf, MarkerAdoptPayload payload) {
            buf.writeLong(payload.id().getMostSignificantBits());
            buf.writeLong(payload.id().getLeastSignificantBits());
        }
    };

    @Override
    public Type<MarkerAdoptPayload> type() {
        return TYPE;
    }
}
