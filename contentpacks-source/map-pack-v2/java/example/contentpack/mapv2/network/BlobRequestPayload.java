package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：按 topic + key 要一块数据。
 *
 * <p>{@code knownRevision} 让服务端在「我这份不比你新」时什么都不发；
 * 上传方向自带内容，不走这个请求。
 */
public record BlobRequestPayload(String topic, String key, long knownRevision)
        implements CustomPacketPayload {

    public static final Type<BlobRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "blob_request"));

    public static final StreamCodec<ByteBuf, BlobRequestPayload> CODEC = new StreamCodec<>() {
        @Override
        public BlobRequestPayload decode(ByteBuf buf) {
            String topic = ByteBufCodecs.STRING_UTF8.decode(buf);
            String key = ByteBufCodecs.STRING_UTF8.decode(buf);
            if (topic.length() > BlobChunkPayload.MAX_TOPIC_CHARS) {
                throw new DecoderException("blob topic too long");
            }
            if (key.length() > BlobChunkPayload.MAX_KEY_CHARS) {
                throw new DecoderException("blob key too long");
            }
            return new BlobRequestPayload(topic, key, buf.readLong());
        }

        @Override
        public void encode(ByteBuf buf, BlobRequestPayload payload) {
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.topic());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.key());
            buf.writeLong(payload.knownRevision());
        }
    };

    @Override
    public Type<BlobRequestPayload> type() {
        return TYPE;
    }
}
