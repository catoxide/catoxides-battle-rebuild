package com.catoxide.item_block_test_pack;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

/**
 * ItemBlockTestPack —— 内容包入口类（骨架由 ContentPack Editor 生成）
 *
 * <p>生命周期（两阶段，勿颠倒）：
 * <ol>
 *   <li>所有已加载包的 {@code register()} 按依赖拓扑序全部执行完；</li>
 *   <li>注册表锁（ContentPackRegistry / CapabilityRegistry / BehaviorRouter）；</li>
 *   <li>最后才轮到所有包的 {@code init()}。</li>
 * </ol>
 * 因此：注册内容放 {@code register()}，消费别的包提供的能力放 {@code init()}。
 *
 * <p>注意：{@code getId()} 是【包内标识】（供日志/能力查询），与 MANIFEST 的
 * {@code Module-Name}（依赖图键）是两个不同的东西。
 *
 * <p>物品/方块（items/*.json + blocks/*.json）由基座批量注册器统一处理：
 * {@code registerItemsAndBlocksFromJar()} 扫描本包 jar，按契约校验并注册
 * （含自定义参数 blockstate、方块实体、BlockItem、创造栏）。
 */
public class ItemBlockTestPack implements ContentPack {

    @Override
    public String getId() {
        return "item_block_test_pack";
    }

    @Override
    public String getDisplayName() {
        return "item-block-test-pack";
    }

    @Override
    public String getVersion() {
        return "0.1.0";
    }

    /** 注册阶段：只做注册（此时其它包的 register() 可能还没跑完）。 */
    @Override
    public void register(ContentPackContext context) {
        // 批量注册本包 items/*.json + blocks/*.json（物品/方块/方块实体/创造栏）
        context.registerItemsAndBlocksFromJar();
    }

    /** 初始化阶段：所有包的 register() 完成、注册表已锁之后才调用。 */
    @Override
    public void init() {
        // TODO 事件监听 / 跨包能力消费放这里，例如：
        // context.capability(OtherCapability.INSTANCE).ifPresent(impl -> ...);
        LogManager.serverInfo(getId(), "initialized");
    }
}
