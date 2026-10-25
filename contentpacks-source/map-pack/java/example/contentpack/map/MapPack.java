package example.contentpack.map;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudElements;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.map.client.MapClientEvents;
import example.contentpack.map.client.MapHudElement;
import example.contentpack.map.network.MapNetwork;
import example.contentpack.map.network.MapServerSync;
import com.catoxide.catoxidesbattlerebuild.core.world.WorldIdentity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 小地图 + 世界地图（纯客户端内容包）。
 *
 * <h3>首版范围</h3>
 * <ul>
 *   <li>top 层：每区块 16×16 列，取 {@code WORLD_SURFACE} 表面方块的 map color + 群系 tint，
 *       按高度做明暗；右上角小地图实时显示。</li>
 *   <li>世界地图：M 键打开，以玩家为中心、滚轮缩放（复用同一份瓦片）。</li>
 *   <li>尚未实现（后续版本）：地下 16y 分层、磁盘持久化、标点、多人共享。</li>
 * </ul>
 *
 * <h3>为什么是纯客户端</h3>
 * 采集只读客户端已加载区块（{@code ChunkStatus.FULL} 且非空），不请求加载、不写世界；
 * 不装本包 = 无采集、无 HUD、无副作用（R3）。
 *
 * <p>A7：内容包不能用 {@code @EventBusSubscriber}，全部事件由
 * {@link MapClientEvents#init} 显式挂载。
 */
public final class MapPack implements ContentPack {

    @Override
    public String getId() {
        return "map-pack";
    }

    @Override
    public String getDisplayName() {
        return "Map";
    }

    @Override
    public String getVersion() {
        return "0.1.0";
    }

    @Override
    public void register(ContentPackContext context) {
        // 两端都要注册：服务端负责发送，客户端负责接收
        MapNetwork.register(context.getModEventBus());

        if (CatoxidesBattleRebuildConstants.CLIENT) {
            MapConfig.bind(context.config("map"));
            HudElements.register(new MapHudElement());
            MapClientEvents.init(context.getModEventBus());
            LogManager.clientStartup("MapPack", "小地图/世界地图已注册（M 键打开世界地图）");
        } else {
            MapServerSync.init();
            // 世界启动时就生成/落盘世界 ID，保证玩家登录前已就绪
            NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                    WorldIdentity.get(event.getServer()));
            LogManager.serverInfo("MapPack", "地图方块变化广播已启用（脏区块通知）");
        }
    }
}
