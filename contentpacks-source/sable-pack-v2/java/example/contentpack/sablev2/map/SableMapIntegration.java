package example.contentpack.sablev2.map;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.CapabilityRegistry;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.map.MapCaps;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.sablev2.map.client.SableMapController;
import example.contentpack.sablev2.map.client.SimulatedDiagramBridge;
import net.neoforged.fml.ModList;

/**
 * sable-pack-v2 的地图联动：把 sub-level 图解层与标点锚定挂到 map-pack-v2。
 *
 * <p>生命周期与 pack 的两阶段一致：
 * <ul>
 *   <li>{@link #register(ContentPackContext)}：绑定配置、跑纯数学自检（register 阶段）；</li>
 *   <li>{@link #init()}：所有包的 register 已完成、能力已锁定，此时再取 {@code map:api}。</li>
 * </ul>
 *
 * <p><b>软依赖</b>：船体图解渲染用 simulated（Create: Aeronautics 随附）的整船渲染入口，
 * 本包不编译依赖它。simulated 缺失或入口不可用时只 shutdown 图解图层并响亮记 WARN；
 * 标点锚定、甲板高度与 sub-level 防腐服务照常工作。
 */
public final class SableMapIntegration {

    private static final String TAG = "SableMap";
    private static final String SIMULATED_MOD_ID = "simulated";

    private SableMapController controller;
    private boolean mathOk = true;

    /** register 阶段：配置只能在此时绑定。 */
    public void register(ContentPackContext context) {
        SableMapConfig.bind(context.config("sablemap"));
        this.mathOk = SableMapSelfTest.run();
    }

    /** init 阶段：能力已就绪，挂 overlay / 坐标解析器。 */
    public void init() {
        if (!this.mathOk) {
            LogManager.clientError(TAG, "Sable map math self-test failed; map layer disabled");
            return;
        }

        boolean shipLayer = this.shipLayerAvailable();
        CapabilityRegistry.get(MapCaps.API).ifPresentOrElse(api -> {
            this.controller = new SableMapController();
            this.controller.register(api, shipLayer);
            if (shipLayer) {
                LogManager.clientStartup(TAG,
                        "Sable 船体图解地图层已注册（GPU 俯视快照 + 船上实体 + 标点锚定）");
            } else {
                LogManager.clientWarn(TAG,
                        "Sable 船体图解图层已 shutdown（缺 simulated）；标点锚定/甲板高度仍可用");
            }
        }, () -> LogManager.clientInfo(TAG,
                "map-pack-v2 未安装或未提供 map:api；Sable 地图层不启用"));
    }

    /** 软依赖门控：mod 存在 + 反射入口可用，任一不满足都关闭图解图层。 */
    private static boolean shipLayerAvailable() {
        if (!ModList.get().isLoaded(SIMULATED_MOD_ID)) {
            LogManager.clientWarn(TAG,
                    "未检测到 simulated（Create: Aeronautics）：船体图解图层 shutdown");
            return false;
        }
        if (!SimulatedDiagramBridge.available()) {
            LogManager.clientWarn(TAG,
                    "simulated 的图解渲染入口不可用：船体图解图层 shutdown");
            return false;
        }
        return true;
    }
}
