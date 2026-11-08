package example.contentpack.tech.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：打开界面时请求一次完整进度。 */
public record TechStateRequestPayload() implements CustomPacketPayload {

    public static final Type<TechStateRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "tech_state_request"));

    public static final StreamCodec<ByteBuf, TechStateRequestPayload> CODEC =
            StreamCodec.unit(new TechStateRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
