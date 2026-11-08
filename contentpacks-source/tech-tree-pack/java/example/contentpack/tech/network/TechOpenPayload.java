package example.contentpack.tech.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：打开科技界面，并绑定到某个研究台（pos + 当前层级）。 */
public record TechOpenPayload(BlockPos pos, int tier) implements CustomPacketPayload {

    public static final Type<TechOpenPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "tech_open"));

    public static final StreamCodec<ByteBuf, TechOpenPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TechOpenPayload::pos,
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT, TechOpenPayload::tier,
            TechOpenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
