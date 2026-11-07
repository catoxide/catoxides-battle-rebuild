package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 分块传输的一片。双端同 id：服务端→客户端是下发，客户端→服务端是上传。
 *
 * <p>每片自带 {@code topic/key/传输 id/片号/总片数/总字节/版本}，不依赖先到的控制包，
 * 乱序、丢片、重复片都能在接收侧判定；{@code parts == 0} 是约定的「目标不存在」标记
 * （例如请求的瓦片服务端也没有），此时 {@code data} 为空。
 *
 * <p>字段全部在解码时校验长度，非法包直接断开连接而不是进入业务层。
 */
public record BlobChunkPayload(UUID transferId, String topic, String key,
                               int part, int parts, int totalBytes, long revision, byte[] data)
        implements CustomPacketPayload {

    public static final int MAX_TOPIC_CHARS = 32;
    public static final int MAX_KEY_CHARS = 160;
    public static final int MAX_DATA_BYTES = 32 * 1024;

    public static final Type<BlobChunkPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "blob_chunk"));

    public static final StreamCodec<ByteBuf, BlobChunkPayload> CODEC = new StreamCodec<>() {
        @Override
        public BlobChunkPayload decode(ByteBuf buf) {
            UUID transferId = new UUID(buf.readLong(), buf.readLong());
            String topic = readString(buf, MAX_TOPIC_CHARS, "topic");
            String key = readString(buf, MAX_KEY_CHARS, "key");
            int part = ByteBufCodecs.VAR_INT.decode(buf);
            int parts = ByteBufCodecs.VAR_INT.decode(buf);
            int totalBytes = ByteBufCodecs.VAR_INT.decode(buf);
            long revision = buf.readLong();
            int length = ByteBufCodecs.VAR_INT.decode(buf);
            if (length < 0 || length > MAX_DATA_BYTES) {
                throw new DecoderException("blob chunk length " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new BlobChunkPayload(transferId, topic, key, part, parts, totalBytes,
                    revision, data);
        }

        @Override
        public void encode(ByteBuf buf, BlobChunkPayload payload) {
            buf.writeLong(payload.transferId().getMostSignificantBits());
            buf.writeLong(payload.transferId().getLeastSignificantBits());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.topic());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.key());
            ByteBufCodecs.VAR_INT.encode(buf, payload.part());
            ByteBufCodecs.VAR_INT.encode(buf, payload.parts());
            ByteBufCodecs.VAR_INT.encode(buf, payload.totalBytes());
            buf.writeLong(payload.revision());
            byte[] data = payload.data() == null ? new byte[0] : payload.data();
            ByteBufCodecs.VAR_INT.encode(buf, data.length);
            buf.writeBytes(data);
        }

        private static String readString(ByteBuf buf, int maxChars, String field) {
            String value = ByteBufCodecs.STRING_UTF8.decode(buf);
            if (value.length() > maxChars) {
                throw new DecoderException("blob " + field + " too long: " + value.length());
            }
            return value;
        }
    };

    /** 「服务端没有这块」的应答；版本 0。 */
    public static BlobChunkPayload absent(String topic, String key) {
        return new BlobChunkPayload(UUID.randomUUID(), topic, key, 0, 0, 0, 0L, new byte[0]);
    }

    public boolean isAbsentMarker() {
        return parts <= 0;
    }

    @Override
    public Type<BlobChunkPayload> type() {
        return TYPE;
    }
}
