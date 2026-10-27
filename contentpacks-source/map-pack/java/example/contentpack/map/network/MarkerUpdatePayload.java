package example.contentpack.map.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 客户端 → 服务端：编辑标记的图标/名字（owner 或 op）。
 * <p>{@code resetIcon = true} 表示回到默认表现（地点标记=三角形，物品/实体=本体图标/头像）。
 */
public record MarkerUpdatePayload(UUID id, ResourceLocation icon, String label, boolean resetIcon)
        implements CustomPacketPayload {

    public static final Type<MarkerUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "marker_update"));

    public static final StreamCodec<ByteBuf, MarkerUpdatePayload> CODEC = new StreamCodec<>() {
        @Override
        public MarkerUpdatePayload decode(ByteBuf buf) {
            UUID id = new UUID(buf.readLong(), buf.readLong());
            ResourceLocation icon = ResourceLocation.parse(ByteBufCodecs.STRING_UTF8.decode(buf));
            String label = ByteBufCodecs.STRING_UTF8.decode(buf);
            boolean resetIcon = buf.readBoolean();
            return new MarkerUpdatePayload(id, icon, label, resetIcon);
        }

        @Override
        public void encode(ByteBuf buf, MarkerUpdatePayload payload) {
            buf.writeLong(payload.id().getMostSignificantBits());
            buf.writeLong(payload.id().getLeastSignificantBits());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.icon().toString());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.label());
            buf.writeBoolean(payload.resetIcon());
        }
    };

    @Override
    public Type<MarkerUpdatePayload> type() {
        return TYPE;
    }
}
