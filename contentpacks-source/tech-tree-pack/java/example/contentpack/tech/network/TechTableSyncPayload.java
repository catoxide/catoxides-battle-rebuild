package example.contentpack.tech.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：研究台层级变化（升级成功后刷新界面）。 */
public record TechTableSyncPayload(BlockPos pos, int tier) implements CustomPacketPayload {

    public static final Type<TechTableSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "tech_table_sync"));

    public static final StreamCodec<ByteBuf, TechTableSyncPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TechTableSyncPayload::pos,
            ByteBufCodecs.VAR_INT, TechTableSyncPayload::tier,
            TechTableSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
