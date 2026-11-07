package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：一批「探明区块」格（格坐标 + 64 位掩码）。
 *
 * <p>快照与增量同形，客户端一律按位 OR，所以重复/乱序都安全。
 * 空数组是一次有效载荷：它表示「服务端开着探明闸门，当前这个维度还没有任何范围」，
 * 客户端据此开始限制预测，而不是等第一条增量才切换。
 */
public record MapRevealPayload(ResourceLocation dimension, long[] cells, long[] masks)
        implements CustomPacketPayload {

    public static final int MAX_ENTRIES = 2048;

    public static final Type<MapRevealPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "reveal"));

    public static final StreamCodec<ByteBuf, MapRevealPayload> CODEC = new StreamCodec<>() {
        @Override
        public MapRevealPayload decode(ByteBuf buf) {
            ResourceLocation dimension = ResourceLocation.parse(
                    ByteBufCodecs.STRING_UTF8.decode(buf));
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            if (count < 0 || count > MAX_ENTRIES) {
                throw new DecoderException("bad reveal entry count: " + count);
            }
            long[] cells = new long[count];
            long[] masks = new long[count];
            for (int i = 0; i < count; i++) {
                cells[i] = buf.readLong();
                masks[i] = buf.readLong();
            }
            return new MapRevealPayload(dimension, cells, masks);
        }

        @Override
        public void encode(ByteBuf buf, MapRevealPayload payload) {
            if (payload.cells().length != payload.masks().length) {
                throw new IllegalArgumentException("reveal arrays must match");
            }
            if (payload.cells().length > MAX_ENTRIES) {
                throw new IllegalArgumentException("reveal batch too large");
            }
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.dimension().toString());
            ByteBufCodecs.VAR_INT.encode(buf, payload.cells().length);
            for (int i = 0; i < payload.cells().length; i++) {
                buf.writeLong(payload.cells()[i]);
                buf.writeLong(payload.masks()[i]);
            }
        }
    };

    @Override
    public Type<MapRevealPayload> type() {
        return TYPE;
    }
}