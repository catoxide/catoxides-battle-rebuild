package example.contentpack.tech.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：按钮提交一条可重复研究项（服务端重新校验条件/冷却/成本）。 */
public record TechSubmitPayload(String researchId, BlockPos pos) implements CustomPacketPayload {

    public static final Type<TechSubmitPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "tech_submit"));

    public static final StreamCodec<ByteBuf, TechSubmitPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TechSubmitPayload::researchId,
            BlockPos.STREAM_CODEC, TechSubmitPayload::pos,
            TechSubmitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
