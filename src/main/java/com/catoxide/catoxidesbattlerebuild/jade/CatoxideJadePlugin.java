package com.catoxide.catoxidesbattlerebuild.jade;

import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade 插件入口（标准显示接入点）。
 * <p>通过 {@code @WailaPlugin} 注解被 Jade 自动发现（NeoForge 无需 entrypoints）。
 * <p>Jade 未安装时本类不会被加载——主 mod 不强依赖 Jade（compileOnly）。
 * <p>职责：把实体骨骼部位血量通过 Jade 显示（服务端数据同步 + 客户端 tooltip）。
 */
@WailaPlugin
public class CatoxideJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerEntityDataProvider(BoneHealthProvider.INSTANCE, AnimatedMob.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(BoneHealthProvider.INSTANCE, AnimatedMob.class);
    }
}
