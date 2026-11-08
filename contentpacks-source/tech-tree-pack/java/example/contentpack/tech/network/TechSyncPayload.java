package example.contentpack.tech.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：玩家科技状态快照。
 * <ul>
 *   <li>{@code unlocked} 已解锁节点</li>
 *   <li>{@code points} 科技点余额</li>
 *   <li>{@code visible} 当前可见节点（visibleWhen 由服务端判定）</li>
 *   <li>{@code met} 当前满足解锁条件的节点</li>
 *   <li>{@code research} 可提交研究项状态（可见/冷却）</li>
 * </ul>
 */
public record TechSyncPayload(List<String> unlocked, int points, List<String> visible,
                              List<String> met, List<ResearchState> research)
        implements CustomPacketPayload {

    public static final Type<TechSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "tech_sync"));

    public static final StreamCodec<ByteBuf, TechSyncPayload> CODEC = new StreamCodec<>() {
        @Override
        public TechSyncPayload decode(ByteBuf buf) {
            List<String> unlocked = readStrings(buf);
            int points = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> visible = readStrings(buf);
            List<String> met = readStrings(buf);
            int size = ByteBufCodecs.VAR_INT.decode(buf);
            List<ResearchState> research = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                research.add(new ResearchState(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        buf.readLong(),
                        ByteBufCodecs.BOOL.decode(buf)));
            }
            return new TechSyncPayload(unlocked, points, visible, met, research);
        }

        @Override
        public void encode(ByteBuf buf, TechSyncPayload payload) {
            writeStrings(buf, payload.unlocked());
            ByteBufCodecs.VAR_INT.encode(buf, payload.points());
            writeStrings(buf, payload.visible());
            writeStrings(buf, payload.met());
            ByteBufCodecs.VAR_INT.encode(buf, payload.research().size());
            for (ResearchState state : payload.research()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, state.id());
                ByteBufCodecs.BOOL.encode(buf, state.visible());
                buf.writeLong(state.readyAtMillis());
                ByteBufCodecs.BOOL.encode(buf, state.ready());
            }
        }
    };

    private static List<String> readStrings(ByteBuf buf) {
        int size = ByteBufCodecs.VAR_INT.decode(buf);
        List<String> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(ByteBufCodecs.STRING_UTF8.decode(buf));
        }
        return List.copyOf(ids);
    }

    private static void writeStrings(ByteBuf buf, List<String> values) {
        ByteBufCodecs.VAR_INT.encode(buf, values.size());
        for (String value : values) {
            ByteBufCodecs.STRING_UTF8.encode(buf, value);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 研究项状态。
     *
     * @param ready          服务端判定此刻是否可提交
     * @param readyAtMillis  冷却结束时刻（0 = 无冷却），客户端用于倒计时
     */
    public record ResearchState(String id, boolean visible, long readyAtMillis, boolean ready) {
    }
}
