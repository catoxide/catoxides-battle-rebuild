package example.contentpack.biomepreview.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：地图需要这块区域的群系预测。
 *
 * <p>预测依赖 worldgen 的 {@code RandomState}，纯客户端连服拿不到，所以必须由服务端算。
 */
public record BiomePreviewRequestPayload(ResourceLocation dimension, int centerX, int centerZ,
                                         int radius, int step) implements CustomPacketPayload {

    public static final Type<BiomePreviewRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "biome_preview_request"));

    public static final StreamCodec<ByteBuf, BiomePreviewRequestPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public BiomePreviewRequestPayload decode(ByteBuf buf) {
                    ResourceLocation dimension = ResourceLocation.parse(
                            ByteBufCodecs.STRING_UTF8.decode(buf));
                    int centerX = buf.readInt();
                    int centerZ = buf.readInt();
                    int radius = ByteBufCodecs.VAR_INT.decode(buf);
                    int step = ByteBufCodecs.VAR_INT.decode(buf);
                    return new BiomePreviewRequestPayload(dimension, centerX, centerZ, radius, step);
                }

                @Override
                public void encode(ByteBuf buf, BiomePreviewRequestPayload payload) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.dimension().toString());
                    buf.writeInt(payload.centerX());
                    buf.writeInt(payload.centerZ());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.radius());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.step());
                }
            };

    @Override
    public Type<BiomePreviewRequestPayload> type() {
        return TYPE;
    }
}
