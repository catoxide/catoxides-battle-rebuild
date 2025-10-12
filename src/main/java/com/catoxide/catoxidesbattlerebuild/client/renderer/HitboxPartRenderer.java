package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.mob.HitboxPart;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class HitboxPartRenderer extends EntityRenderer<HitboxPart> {

    public HitboxPartRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(HitboxPart livingEntity, Frustum camera, double camX, double camY, double camZ) {
        // 不渲染这个实体
        return false;
    }

    @Override
    public ResourceLocation getTextureLocation(HitboxPart entity) {
        // 返回一个空的纹理位置
        return new ResourceLocation("minecraft", "textures/block/stone.png");
    }
}