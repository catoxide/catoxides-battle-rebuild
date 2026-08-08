package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * ContentPack 体系的事件订阅（MOD 总线）
 * <p>把已注册 contentpack 的 jar 挂进资源树（资源包 + 数据包）。
 * 触发时机：PackRepository 创建时（晚于主 mod 构造函数中的 ContentPackLoader.loadAll，
 * 因此 ContentPackRegistry 此时已填充完毕）。
 */
@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ContentPackEvents {

    private ContentPackEvents() {}

    @SubscribeEvent
    public static void addPackFinders(AddPackFindersEvent event) {
        event.addRepositorySource(new ContentPackRepositorySource(event.getPackType()));
    }
}
