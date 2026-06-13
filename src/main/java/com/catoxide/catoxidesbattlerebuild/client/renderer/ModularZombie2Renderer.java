package com.catoxide.catoxidesbattlerebuild.client.renderer;

import cn.solarmoon.spark_core.animation.renderer.GeoLivingEntityRenderer;
import com.catoxide.catoxidesbattlerebuild.mob.zombie2.ModularZombie2;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

public class ModularZombie2Renderer extends GeoLivingEntityRenderer<ModularZombie2> {
    public ModularZombie2Renderer(EntityRendererProvider.Context context) {
        super(context, 0.5f);
        LogManager.clientStartup("ModularZombie2Renderer", "ModularZombie2Renderer initialized (SparkCore GeoLivingEntityRenderer)");
    }
}