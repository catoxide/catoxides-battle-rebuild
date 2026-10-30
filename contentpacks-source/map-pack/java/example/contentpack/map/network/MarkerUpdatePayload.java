package example.contentpack.map.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 客户端 → 服务端：编辑标记的图标/名字/坐标/呈现方式（owner 或 op）。
 * <p>{@code resetIcon = true} 表示回到默认表现（地点标记=三角形，物品/实体=本体图标/头像）。
 * <p>{@code x/y/z} 与 {@code showName} 只对地点标记（waypoint）生效，其它类型服务端忽略；
 * {@code compass} 对所有类型都生效。
 */
public record MarkerUpdatePayload(UUID id, ResourceLocation icon, String label, boolean resetIcon,
                                  double x, double y, double z, boolean showName, boolean compass)
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
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            boolean showName = buf.readBoolean();
            boolean compass = buf.readBoolean();
            return new MarkerUpdatePayload(id, icon, label, resetIcon, x, y, z, showName, compass);
        }

        @Override
        public void encode(ByteBuf buf, MarkerUpdatePayload payload) {
            buf.writeLong(payload.id().getMostSignificantBits());
            buf.writeLong(payload.id().getLeastSignificantBits());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.icon().toString());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.label());
            buf.writeBoolean(payload.resetIcon());
            buf.writeDouble(payload.x());
            buf.writeDouble(payload.y());
            buf.writeDouble(payload.z());
            buf.writeBoolean(payload.showName());
            buf.writeBoolean(payload.compass());
        }
    };

    @Override
    public Type<MarkerUpdatePayload> type() {
        return TYPE;
    }
}
