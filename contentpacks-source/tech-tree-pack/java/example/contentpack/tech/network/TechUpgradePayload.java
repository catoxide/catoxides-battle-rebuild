package example.contentpack.tech.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：请求升级所绑定的研究台（服务端重新校验位置与成本）。 */
public record TechUpgradePayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<TechUpgradePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "tech_upgrade"));

    public static final StreamCodec<ByteBuf, TechUpgradePayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TechUpgradePayload::pos,
            TechUpgradePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
