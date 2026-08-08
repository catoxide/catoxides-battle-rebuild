package com.catoxide.catoxidesbattlerebuild.client.renderer;

import cn.solarmoon.spark_core.animation.renderer.GeoLivingEntityRenderer;
import com.catoxide.catoxidesbattlerebuild.core.mob.DataDrivenMob;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

/**
 * 数据驱动实体通用渲染器
 * <p>所有 {@link DataDrivenMob}（JSON 定义实体）共用此渲染器。
 * 模型/贴图/动画由实体自身（MobDefinition）提供，无需每个实体单独写渲染器。
 */
public class DataDrivenMobRenderer extends GeoLivingEntityRenderer<DataDrivenMob> {
    public DataDrivenMobRenderer(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context) {
        super(context, 0.5f);
        LogManager.clientStartup("DataDrivenMobRenderer", "Initialized (generic, data-driven)");
    }
}
