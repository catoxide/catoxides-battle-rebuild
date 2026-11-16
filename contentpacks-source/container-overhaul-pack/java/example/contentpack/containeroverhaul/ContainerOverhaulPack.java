package example.contentpack.containeroverhaul;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.container.ContainerPolicies;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.ui.UiEditTargets;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.containeroverhaul.client.SidePanelLayout;
import example.contentpack.containeroverhaul.client.SidePanelRenderer;
import example.contentpack.containeroverhaul.ui.SidePanelCommands;
import example.contentpack.containeroverhaul.ui.SidePanelMounts;
import example.contentpack.containeroverhaul.ui.SidePanelSelfTest;

/**
 * 容器大改。
 *
 * <p>当前切片：
 * <ul>
 *   <li>战利品箱不可拆卸/不可置入 + 掉落物 45s 消失；</li>
 *   <li>原版容器屏右侧的附属面板（3×3 暂存 + 2 升级槽），挂在玩家物品栏与箱子上；
 *       位置在 hud-layout-pack 的 F8 容器编辑模式里拖动，偏移存 {@code ui-layout.json}；</li>
 * </ul>
 *
 * <p>箱子容量升级与玩家背包格数不在这里 —— 前者要决定初始容量/升级物/双箱语义，
 * 后者依赖通用容器 UI（uiconfig type 2），本包先落不依赖界面的规则。
 */
public final class ContainerOverhaulPack implements ContentPack {

    public static final String ID = "container-overhaul-pack";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Container Overhaul";
    }

    @Override
    public String getVersion() {
        return "0.2.0";
    }

    @Override
    public void register(ContentPackContext context) {
        LootChests.registerAttachmentType(context.getModEventBus());
        SidePanelMounts.register();
        if (CatoxidesBattleRebuildConstants.CLIENT) {
            SidePanelRenderer.init();
            UiEditTargets.register(SidePanelLayout.INSTANCE);
        }
        LogManager.serverInfo(ID, "Loot-chest marker + side panel registered");
    }

    @Override
    public void init() {
        ContainerPolicies.register(LootChests.POLICY);
        LootChests.installEventListeners();
        DroppedItemLifetime.install();
        SidePanelCommands.install();
        SidePanelSelfTest.run();
        LogManager.serverInfo(ID, "Loot chests locked; dropped items live %d ticks", DroppedItemLifetime.DESPAWN_TICKS);
    }
}
