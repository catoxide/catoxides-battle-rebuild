package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：创建一个标记。
 *
 * <p>{@code data} 是扩展载荷（ITEM 时为 ItemStack NBT）；{@code targetKey} 用于服务端去重/刷新。
 */
public record MarkerCreatePayload(String kind, ResourceLocation icon,
                                  double x, double y, double z,
                                  int entityId, int slot, String targetKey,
                                  CompoundTag data, boolean sticky)
        implements CustomPacketPayload {

    public static final Type<MarkerCreatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "marker_create"));

    public static final StreamCodec<ByteBuf, MarkerCreatePayload> CODEC = new StreamCodec<>() {
        @Override
        public MarkerCreatePayload decode(ByteBuf buf) {
            String type = ByteBufCodecs.STRING_UTF8.decode(buf);
            ResourceLocation icon = ResourceLocation.parse(ByteBufCodecs.STRING_UTF8.decode(buf));
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            int entityId = buf.readInt();
            int slot = buf.readInt();
            String targetKey = ByteBufCodecs.STRING_UTF8.decode(buf);
            Tag raw = ByteBufCodecs.TAG.decode(buf);
            CompoundTag data = raw instanceof CompoundTag compound ? compound : new CompoundTag();
            boolean sticky = buf.readBoolean();
            return new MarkerCreatePayload(type, icon, x, y, z, entityId, slot,
                    targetKey, data, sticky);
        }

        @Override
        public void encode(ByteBuf buf, MarkerCreatePayload payload) {
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.kind());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.icon().toString());
            buf.writeDouble(payload.x());
            buf.writeDouble(payload.y());
            buf.writeDouble(payload.z());
            buf.writeInt(payload.entityId());
            buf.writeInt(payload.slot());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.targetKey());
            ByteBufCodecs.TAG.encode(buf, payload.data());
            buf.writeBoolean(payload.sticky());
        }
    };

    @Override
    public Type<MarkerCreatePayload> type() {
        return TYPE;
    }
}
