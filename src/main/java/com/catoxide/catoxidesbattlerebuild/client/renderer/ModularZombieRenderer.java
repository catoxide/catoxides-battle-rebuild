package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.client.model.ModularZombieModel;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * ModularZombie 的 GeckoLib 渲染器类
 * 负责渲染 ModularZombie 实体的 3D 模型
 */
public class ModularZombieRenderer extends GeoEntityRenderer<ModularZombie> {

    public ModularZombieRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new ModularZombieModel());

        // 设置实体阴影大小
        this.shadowRadius = 0.5f;

        // 可以在这里添加更多渲染设置
        // 比如发光效果、缩放等
    }
}
