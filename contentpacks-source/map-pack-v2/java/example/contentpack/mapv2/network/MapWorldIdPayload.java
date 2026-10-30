package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 服务端 → 客户端：当前世界的身份信息。
 *
 * <ul>
 *   <li>{@code worldId} —— 存档级 UUID，正常情况直接用它匹配地图目录；</li>
 *   <li>{@code seed + generator} —— UUID 对不上时（复制存档/回档/打包部署），
 *       用来找「地形可能一致的已有地图」并让玩家选择是否继承；</li>
 *   <li>{@code worldName} —— 候选列表里给人看的名字。</li>
 * </ul>
 */
public record MapWorldIdPayload(UUID worldId, long seed, String generator, String worldName)
        implements CustomPacketPayload {

    public static final Type<MapWorldIdPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "map_world_id"));

    public static final StreamCodec<ByteBuf, MapWorldIdPayload> CODEC = new StreamCodec<>() {
        @Override
        public MapWorldIdPayload decode(ByteBuf buf) {
            UUID worldId = new UUID(buf.readLong(), buf.readLong());
            long seed = buf.readLong();
            String generator = ByteBufCodecs.STRING_UTF8.decode(buf);
            String name = ByteBufCodecs.STRING_UTF8.decode(buf);
            return new MapWorldIdPayload(worldId, seed, generator, name);
        }

        @Override
        public void encode(ByteBuf buf, MapWorldIdPayload payload) {
            buf.writeLong(payload.worldId().getMostSignificantBits());
            buf.writeLong(payload.worldId().getLeastSignificantBits());
            buf.writeLong(payload.seed());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.generator());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.worldName());
        }
    };

    @Override
    public Type<MapWorldIdPayload> type() {
        return TYPE;
    }
}
