package example.contentpack.compass;

import example.contentpack.compass.client.CompassClientEvents;
import example.contentpack.compass.client.CompassConfig;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

/**
 * 屏幕顶罗盘（TASKS.md §6 #1）—— 端口自 DAIBERU/Skyrim-Compass-HUD（MIT © 2026 Daiberu）。
 *
 * <h3>内容</h3>
 * <ul>
 *   <li>顶部横排罗盘条：角度读数 + 8 方位（N/NE/E/SE/S/SW/W/NW）+ 15° 刻度 + 固定中指针</li>
 *   <li>H 键开关（lang: key.skyrimcompasshud.toggle）</li>
 *   <li>样式配置（{@code config-declarations.json} → run\client\config\compass-pack-client.toml，
 *       全部 HOT，改完即生效）：①刻度（均匀15°↔按FOV模拟实际）②简化版（仅方位数字）③条厚（正常↔厚）④背景（磨砂黑↔无）</li>
 * </ul>
 *
 * <h3>A7（内容包显式注册）</h3>
 * 内容包不能用 @EventBusSubscriber（FML 只扫主 mod jar）——
 * 客户端事件全显式 addListener；layer + 按键 注册到 <b>mod bus</b>（两者均为 IModBusEvent），
 * ClientTickEvent 注册到 <b>全局 NeoForge.EVENT_BUS</b>；均在 {@link #register} 内、CLIENT 下接线。
 *
 * <h3>R3</h3>
 * 纯客户端 overlay：无方块/实体/物品，register() 不做内容注册，仅接线客户端事件。
 * 不装本包 = 无罗盘，零副作用。
 */
public class CompassPack implements ContentPack {

    @Override
    public String getId() {
        return "compass-pack";
    }

    @Override
    public String getDisplayName() {
        return "Skyrim Compass HUD (MIT port)";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void register(ContentPackContext context) {
        // 纯客户端 overlay：无 server 内容注册。
        if (CatoxidesBattleRebuildConstants.CLIENT) {
            // 配置由包 jar 的 config-declarations.json 声明，主 mod 已编译并注册到
            // run\client\config\compass-pack-client.toml；这里只拿读值句柄。
            CompassConfig.bind(context.config("hud"));
            CompassClientEvents.init(context.getModEventBus());
            LogManager.clientStartup("CompassPack",
                    "顶部罗盘已注册（H 键切换；样式配置 compass-pack-client.toml）");
        } else {
            LogManager.serverInfo("CompassPack", "纯客户端 overlay，服务器端无注册");
        }
    }
}
