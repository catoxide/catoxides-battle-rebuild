package example.contentpack.tech.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：请求解锁某个节点（服务端按绑定的研究台重新校验层级与成本）。 */
public record TechUnlockPayload(String nodeId, BlockPos pos) implements CustomPacketPayload {

    public static final Type<TechUnlockPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "tech_unlock"));

    public static final StreamCodec<ByteBuf, TechUnlockPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TechUnlockPayload::nodeId,
            BlockPos.STREAM_CODEC, TechUnlockPayload::pos,
            TechUnlockPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
