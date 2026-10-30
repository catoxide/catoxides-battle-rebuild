package example.contentpack.hudlayout;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudLayoutApi;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.hudlayout.client.HudEditorConfig;
import example.contentpack.hudlayout.client.HudLayoutClient;
import example.contentpack.hudlayout.client.HudLayoutSelfTest;
import example.contentpack.hudlayout.layout.HudLayoutService;
import example.contentpack.hudlayout.layout.LayoutStore;

/**
 * HUD 布局（纯客户端内容包）：F8 打开拖拽编辑屏，把屏幕元素按组挪位置。
 *
 * <h3>首版范围</h3>
 * <ul>
 *   <li>只接管两组：hotbar 组（hotbar + 血量/盔甲/饥饿/空气/载具/经验/物品名）与状态效果。
 *       准星、聊天、Boss 条等不动——没有手柄、也不平移。</li>
 *   <li>原版具名图层用 {@code RegisterGuiLayersEvent#wrapLayer} 整体平移；hotbar 与血条同组，
 *       因为原版靠 {@code leftHeight/rightHeight} 堆叠，拆开必错位。</li>
 *   <li>偏移稀疏存在 {@code config/catoxidesbattlerebuild/hud-layout.json}，打包后仍可手改。</li>
 *   <li>其他包自绘的 HUD 通过 core 的 {@code HudElements} 登记 → 出现在同一个编辑屏里；
 *       渲染时查询 {@code HudLayoutApi.offsetX/offsetY}。没有本包时那些包退回原位置（R3）。</li>
 *   <li>每组的<b>移动轴</b>可在右键菜单里锁成「仅左右 / 仅上下 / 自由」，与偏移同存一份文件。</li>
 *   <li>元素还能声明 {@code options()}（→ 面板里读写它所属包的配置）和 {@code resize()}
 *       （→ 边角拉伸手柄，改写它自己那条尺寸配置）。两者都是声明：没声明的元素就是一块可拖的矩形。</li>
 * </ul>
 *
 * <h3>A7</h3>
 * 内容包不能用 {@code @EventBusSubscriber}：全部事件由 {@link HudLayoutClient#init} 显式挂载。
 */
public final class HudLayoutPack implements ContentPack {

    @Override
    public String getId() {
        return "hud-layout-pack";
    }

    @Override
    public String getDisplayName() {
        return "HUD Layout";
    }

    @Override
    public String getVersion() {
        return "0.1.0";
    }

    @Override
    public void register(ContentPackContext context) {
        if (!CatoxidesBattleRebuildConstants.CLIENT) {
            LogManager.serverInfo("HudLayout", "纯客户端 HUD 布局包，服务器端无注册");
            return;
        }

        HudEditorConfig.bind(context.config("editor"));
        LayoutStore.load();
        HudLayoutApi.install(HudLayoutService.INSTANCE);
        HudLayoutClient.init(context.getModEventBus());
        HudLayoutSelfTest.run();
        LogManager.clientStartup("HudLayout",
                "HUD 布局已注册（F8 打开编辑屏；偏移存 config/catoxidesbattlerebuild/hud-layout.json）");
    }
}
