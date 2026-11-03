package example.contentpack.mapv2;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudElements;
import com.catoxide.catoxidesbattlerebuild.core.map.MapCaps;
import com.catoxide.catoxidesbattlerebuild.core.world.WorldIdentity;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.mapv2.client.CompassConfig;
import example.contentpack.mapv2.client.CompassHudElement;
import example.contentpack.mapv2.client.MapV2ClientEvents;
import example.contentpack.mapv2.client.MinimapHudElement;
import example.contentpack.mapv2.client.MinimapInfoHudElement;
import example.contentpack.mapv2.marker.MarkerSelfTest;
import example.contentpack.mapv2.marker.MarkerServerConfig;
import example.contentpack.mapv2.marker.MarkerService;
import example.contentpack.mapv2.network.MapNetwork;
import example.contentpack.mapv2.network.MapServerSync;
import example.contentpack.mapv2.network.MarkerNetwork;
import example.contentpack.mapv2.structure.StructureSelfTest;
import example.contentpack.mapv2.structure.StructureServerConfig;
import example.contentpack.mapv2.structure.StructureVerdicts;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 小地图 + 世界地图 + 罗盘 + 标记 + 群系预测（重写版）。
 *
 * <p>画布按<b>屏幕像素</b>绘制，缩放档位即 {@code blocks per canvas pixel}，LOD 由
 * {@link example.contentpack.mapv2.view.MapZoom#lod} 一并决定，不再依赖 Minecraft 的 GUI scale。
 * 见 {@code design/MAP_PACK_V2_DESIGN.md}。
 *
 * <p>当前阶段：世界地图（P1）+ 小地图/罗盘/标记（P3）。群系预测的视距式控制（P2）与多实例同步（P4）待做。
 */
public final class MapV2Pack implements ContentPack {

    @Override
    public String getId() {
        return "map-pack-v2";
    }

    @Override
    public String getDisplayName() {
        return "Map v2";
    }

    @Override
    public String getVersion() {
        return "0.1.0";
    }

    @Override
    public void register(ContentPackContext context) {
        // 两端都要注册：服务端发世界 ID / 脏区块 / 标记同步，客户端接收
        MapNetwork.register(context.getModEventBus());
        MarkerNetwork.register(context.getModEventBus());

        MapConfig.bind(context.config("map"));
        // 标记服务端逻辑在集成服务器（单机）里也必须跑：CLIENT 是 JVM 级常量，
        // 不能用来区分「这一侧是不是服务端」，否则单机没有 TTL 清理/死亡点/登录同步/实体发光。
        MarkerServerConfig.bind(context.config("server"));
        StructureServerConfig.bind(context.config("server"));
        MarkerService.init();
        StructureVerdicts.init();
        MarkerSelfTest.run();
        StructureSelfTest.run();
        // 世界启动时就把世界 ID 生成/落盘，保证玩家登录前已就绪
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                WorldIdentity.get(event.getServer()));

        if (CatoxidesBattleRebuildConstants.CLIENT) {
            CompassConfig.bind(context.config("map"));
            MapV2SelfTest.run();
            // 地图扩展能力：必须早于依赖 map-pack-v2 的包 register（由 Module-Dependencies 保证顺序）
            context.provide(MapCaps.API, MapHooks.INSTANCE);
            // 三个 HUD 组件各自独立分组：拖动任何一个只移动它自己
            HudElements.register(new MinimapHudElement());
            HudElements.register(new MinimapInfoHudElement());
            HudElements.register(new CompassHudElement());
            MapV2ClientEvents.init(context.getModEventBus());
            LogManager.clientStartup("MapPackV2",
                    "世界地图/小地图/罗盘/标记已注册（M 开图，H 开关罗盘，Mouse4 标记）");
        } else {
            // 专用服务器才需要广播方块变化：单机客户端自己经 BlockChangeHooks 收到更新
            MapServerSync.init();
        }
        LogManager.serverInfo("MapPackV2", "地图方块变化广播 + 标记服务已启用");
    }
}
