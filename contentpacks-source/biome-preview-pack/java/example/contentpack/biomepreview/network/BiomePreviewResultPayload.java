package example.contentpack.biomepreview.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：一块预测图。
 *
 * <p>调色板直接下发颜色（服务端算好 ARGB），客户端不需要再解析群系注册表，
 * 也能避免两侧注册表不一致时的颜色分歧。
 */
public record BiomePreviewResultPayload(ResourceLocation dimension, int centerX, int centerZ,
                                        int radius, int step, int size,
                                        int[] paletteColors, String[] paletteBiomes, short[] cells)
        implements CustomPacketPayload {

    public static final Type<BiomePreviewResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "biome_preview_result"));

    public static final StreamCodec<ByteBuf, BiomePreviewResultPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public BiomePreviewResultPayload decode(ByteBuf buf) {
                    ResourceLocation dimension = ResourceLocation.parse(
                            ByteBufCodecs.STRING_UTF8.decode(buf));
                    int centerX = buf.readInt();
                    int centerZ = buf.readInt();
                    int radius = ByteBufCodecs.VAR_INT.decode(buf);
                    int step = ByteBufCodecs.VAR_INT.decode(buf);
                    int size = ByteBufCodecs.VAR_INT.decode(buf);
                    int paletteSize = ByteBufCodecs.VAR_INT.decode(buf);
                    int[] palette = new int[paletteSize];
                    for (int i = 0; i < paletteSize; i++) {
                        palette[i] = buf.readInt();
                    }
                    String[] biomes = new String[paletteSize];
                    for (int i = 0; i < paletteSize; i++) {
                        biomes[i] = ByteBufCodecs.STRING_UTF8.decode(buf);
                    }
                    short[] cells = new short[size * size];
                    for (int i = 0; i < cells.length; i++) {
                        cells[i] = buf.readShort();
                    }
                    return new BiomePreviewResultPayload(dimension, centerX, centerZ,
                            radius, step, size, palette, biomes, cells);
                }

                @Override
                public void encode(ByteBuf buf, BiomePreviewResultPayload payload) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.dimension().toString());
                    buf.writeInt(payload.centerX());
                    buf.writeInt(payload.centerZ());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.radius());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.step());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.size());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.paletteColors().length);
                    for (int color : payload.paletteColors()) {
                        buf.writeInt(color);
                    }
                    for (String biome : payload.paletteBiomes()) {
                        ByteBufCodecs.STRING_UTF8.encode(buf, biome);
                    }
                    for (short cell : payload.cells()) {
                        buf.writeShort(cell);
                    }
                }
            };

    @Override
    public Type<BiomePreviewResultPayload> type() {
        return TYPE;
    }
}
