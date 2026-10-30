package example.contentpack.map;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudElements;
import com.catoxide.catoxidesbattlerebuild.core.map.MapCaps;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.map.client.CompassConfig;
import example.contentpack.map.client.CompassHudElement;
import example.contentpack.map.client.MapClientEvents;
import example.contentpack.map.client.MapHudElement;
import example.contentpack.map.marker.MarkerServerConfig;
import example.contentpack.map.marker.MarkerService;
import example.contentpack.map.network.MapNetwork;
import example.contentpack.map.network.MapServerSync;
import example.contentpack.map.network.MarkerNetwork;
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
        MarkerNetwork.register(context.getModEventBus());

        // 标记服务端逻辑在集成服务器（单机）里也必须跑：CLIENT 是 JVM 级常量，
        // 不能用来区分“这一侧是不是服务端”，否则单机没有 TTL 清理/死亡点/登录同步/实体发光。
        MarkerServerConfig.bind(context.config("server"));
        MarkerService.init();
        example.contentpack.map.marker.MarkerSelfTest.run();
        // 世界启动时就生成/落盘世界 ID，保证玩家登录前已就绪
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                WorldIdentity.get(event.getServer()));

        if (CatoxidesBattleRebuildConstants.CLIENT) {
            MapConfig.bind(context.config("map"));
            CompassConfig.bind(context.config("map"));
            MapSelfTest.run();
            // 地图扩展能力：必须早于依赖 map-pack 的包 register（由 Module-Dependencies 保证顺序）
            context.provide(MapCaps.API, MapHooks.INSTANCE);
            HudElements.register(new MapHudElement());
            HudElements.register(new CompassHudElement());
            MapClientEvents.init(context.getModEventBus());
            LogManager.clientStartup("MapPack", "小地图/世界地图已注册（M 键打开世界地图；map:api 能力已提供）");
        } else {
            // 专用服务器才需要广播方块变化：单机客户端自己经 BlockChangeHooks 收到更新
            MapServerSync.init();
        }
        LogManager.serverInfo("MapPack", "地图方块变化广播 + 标记服务已启用");
    }
}
