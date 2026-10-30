package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：哪些区块发生了方块变化，需要重采。
 *
 * <p>只发「脏区块坐标」，不发像素/方块数据——客户端收到后自己从已加载区块重采。
 * 一个包最多带几十个区块，按玩家附近过滤后发送。
 */
public record MapDirtyPayload(ResourceLocation dimension, List<Long> chunks)
        implements CustomPacketPayload {

    public static final Type<MapDirtyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "map_dirty"));

    public static final StreamCodec<ByteBuf, MapDirtyPayload> CODEC = new StreamCodec<>() {
        @Override
        public MapDirtyPayload decode(ByteBuf buf) {
            ResourceLocation dimension = ResourceLocation.parse(
                    ByteBufCodecs.STRING_UTF8.decode(buf));
            int size = ByteBufCodecs.VAR_INT.decode(buf);
            List<Long> chunks = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                chunks.add(buf.readLong());
            }
            return new MapDirtyPayload(dimension, List.copyOf(chunks));
        }

        @Override
        public void encode(ByteBuf buf, MapDirtyPayload payload) {
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.dimension().toString());
            ByteBufCodecs.VAR_INT.encode(buf, payload.chunks().size());
            for (long chunk : payload.chunks()) {
                buf.writeLong(chunk);
            }
        }
    };

    @Override
    public Type<MapDirtyPayload> type() {
        return TYPE;
    }
}
