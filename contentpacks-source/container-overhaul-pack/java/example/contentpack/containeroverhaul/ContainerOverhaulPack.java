package example.contentpack.containeroverhaul;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.container.ContainerPolicies;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.containeroverhaul.client.ContainerCut;
import example.contentpack.containeroverhaul.client.ContainerScreenGate;
import example.contentpack.containeroverhaul.client.ContainerUiRenderer;
import example.contentpack.containeroverhaul.client.SidePanelLayout;
import example.contentpack.containeroverhaul.ui.InventoryCapacityPolicy;
import example.contentpack.containeroverhaul.ui.InventoryMigration;
import example.contentpack.containeroverhaul.ui.SidePanelCommands;
import example.contentpack.containeroverhaul.ui.SidePanelMounts;
import example.contentpack.containeroverhaul.ui.SidePanelSelfTest;

/**
 * 容器大改。
 *
 * <p>当前切片：
 * <ul>
 *   <li>战利品箱不可拆卸/不可置入 + 掉落物 45s 消失；</li>
 *   <li>玩家物品栏扩到 45 格（快捷栏 9 + 背包 4 行），用升级槽逐行解锁；锁住的格不可见、
 *       不可放入/取出，所有自动放置都跳过；</li>
 *   <li>物品栏屏与箱子屏截掉原版下半块，物品格全部挪到右侧悬浮板：快捷栏在底、背包行往上长，
 *       底板随解锁行数换整板贴图；</li>
 *   <li>原侧面板的储物取消，只留 4 格升级浮板；旧储物由登录迁移 + {@code /containeroverhaul
 *       sidepanel recover} 搬进物品栏。</li>
 * </ul>
 *
 * <p>其余 15 个容器菜单的悬浮物品栏、创造模式物品栏不在本切片内。
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
        return "0.3.0";
    }

    @Override
    public void register(ContentPackContext context) {
        LootChests.registerAttachmentType(context.getModEventBus());
        InventoryCapacityPolicy.register();
        SidePanelMounts.register();
        if (CatoxidesBattleRebuildConstants.CLIENT) {
            ContainerScreenGate.install();
            ContainerUiRenderer.init();
            SidePanelLayout.register();
            ContainerCut.register();
        }
        LogManager.serverInfo(ID, "Loot-chest marker + floating inventory registered");
    }

    @Override
    public void init() {
        ContainerPolicies.register(LootChests.POLICY);
        LootChests.installEventListeners();
        DroppedItemLifetime.install();
        InventoryMigration.install();
        SidePanelCommands.install();
        SidePanelSelfTest.run();
        LogManager.serverInfo(ID, "Loot chests locked; dropped items live %d ticks", DroppedItemLifetime.DESPAWN_TICKS);
    }
}
