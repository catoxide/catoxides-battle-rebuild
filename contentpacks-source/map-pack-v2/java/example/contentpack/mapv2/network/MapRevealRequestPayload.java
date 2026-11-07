package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：要某个维度的探明范围快照。
 *
 * <p>只在进入维度时发一次；之后靠服务端增量广播。服务端关闭探明功能时不回包，
 * 客户端保持「不限制」的旧行为。
 */
public record MapRevealRequestPayload(ResourceLocation dimension)
        implements CustomPacketPayload {

    public static final Type<MapRevealRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "reveal_request"));

    public static final StreamCodec<ByteBuf, MapRevealRequestPayload> CODEC = new StreamCodec<>() {
        @Override
        public MapRevealRequestPayload decode(ByteBuf buf) {
            return new MapRevealRequestPayload(
                    ResourceLocation.parse(ByteBufCodecs.STRING_UTF8.decode(buf)));
        }

        @Override
        public void encode(ByteBuf buf, MapRevealRequestPayload payload) {
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.dimension().toString());
        }
    };

    @Override
    public Type<MapRevealRequestPayload> type() {
        return TYPE;
    }
}