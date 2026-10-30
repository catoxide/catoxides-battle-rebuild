package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import example.contentpack.mapv2.marker.MarkerDto;
import example.contentpack.mapv2.marker.MarkerType;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端：标记增量/全量同步。
 *
 * <p>到期时间下发的是**剩余毫秒**（{@code remainingMillis}，0 = 永久），
 * 客户端用本地时钟记 {@code now + remaining}，避免服务器/客户端机器时钟不一致。
 */
public record MarkerSyncPayload(List<Entry> upserts, List<UUID> removals, boolean full)
        implements CustomPacketPayload {

    public record Entry(MarkerDto marker, long remainingMillis) {
    }

    public static final Type<MarkerSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "marker_sync"));

    public static final StreamCodec<ByteBuf, MarkerSyncPayload> CODEC = new StreamCodec<>() {
        @Override
        public MarkerSyncPayload decode(ByteBuf buf) {
            int upsertCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<Entry> upserts = new ArrayList<>(upsertCount);
            for (int i = 0; i < upsertCount; i++) {
                upserts.add(new Entry(readMarker(buf), buf.readLong()));
            }
            int removalCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<UUID> removals = new ArrayList<>(removalCount);
            for (int i = 0; i < removalCount; i++) {
                removals.add(new UUID(buf.readLong(), buf.readLong()));
            }
            boolean full = buf.readBoolean();
            return new MarkerSyncPayload(List.copyOf(upserts), List.copyOf(removals), full);
        }

        @Override
        public void encode(ByteBuf buf, MarkerSyncPayload payload) {
            ByteBufCodecs.VAR_INT.encode(buf, payload.upserts().size());
            for (Entry entry : payload.upserts()) {
                writeMarker(buf, entry.marker());
                buf.writeLong(entry.remainingMillis());
            }
            ByteBufCodecs.VAR_INT.encode(buf, payload.removals().size());
            for (UUID id : payload.removals()) {
                buf.writeLong(id.getMostSignificantBits());
                buf.writeLong(id.getLeastSignificantBits());
            }
            buf.writeBoolean(payload.full());
        }
    };

    private static void writeMarker(ByteBuf buf, MarkerDto marker) {
        writeUuid(buf, marker.id());
        writeUuid(buf, marker.owner());
        ByteBufCodecs.STRING_UTF8.encode(buf, marker.type().name());
        ByteBufCodecs.STRING_UTF8.encode(buf, marker.icon().toString());
        ByteBufCodecs.STRING_UTF8.encode(buf, marker.dimension().toString());
        buf.writeDouble(marker.x());
        buf.writeDouble(marker.y());
        buf.writeDouble(marker.z());
        buf.writeInt(marker.entityId());
        buf.writeInt(marker.slot());
        ByteBufCodecs.STRING_UTF8.encode(buf, marker.targetKey());
        ByteBufCodecs.TAG.encode(buf, marker.data());
        buf.writeLong(marker.createdAt());
        buf.writeLong(marker.expiresAt());
    }

    private static MarkerDto readMarker(ByteBuf buf) {
        UUID id = readUuid(buf);
        UUID owner = readUuid(buf);
        MarkerType type = MarkerType.byName(ByteBufCodecs.STRING_UTF8.decode(buf));
        ResourceLocation icon = ResourceLocation.parse(ByteBufCodecs.STRING_UTF8.decode(buf));
        ResourceLocation dimension = ResourceLocation.parse(ByteBufCodecs.STRING_UTF8.decode(buf));
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        int entityId = buf.readInt();
        int slot = buf.readInt();
        String targetKey = ByteBufCodecs.STRING_UTF8.decode(buf);
        Tag raw = ByteBufCodecs.TAG.decode(buf);
        CompoundTag data = raw instanceof CompoundTag compound ? compound : new CompoundTag();
        long createdAt = buf.readLong();
        long expiresAt = buf.readLong();
        return new MarkerDto(id, owner, type, icon, dimension, x, y, z,
                entityId, slot, targetKey, data, createdAt, expiresAt);
    }

    private static void writeUuid(ByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    @Override
    public Type<MarkerSyncPayload> type() {
        return TYPE;
    }
}
