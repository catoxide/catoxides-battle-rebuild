package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** 客户端 → 服务端：删除一个标记（owner 或 op）。 */
public record MarkerRemovePayload(UUID id) implements CustomPacketPayload {

    public static final Type<MarkerRemovePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "marker_remove"));

    public static final StreamCodec<ByteBuf, MarkerRemovePayload> CODEC = new StreamCodec<>() {
        @Override
        public MarkerRemovePayload decode(ByteBuf buf) {
            return new MarkerRemovePayload(new UUID(buf.readLong(), buf.readLong()));
        }

        @Override
        public void encode(ByteBuf buf, MarkerRemovePayload payload) {
            buf.writeLong(payload.id().getMostSignificantBits());
            buf.writeLong(payload.id().getLeastSignificantBits());
        }
    };

    @Override
    public Type<MarkerRemovePayload> type() {
        return TYPE;
    }
}
