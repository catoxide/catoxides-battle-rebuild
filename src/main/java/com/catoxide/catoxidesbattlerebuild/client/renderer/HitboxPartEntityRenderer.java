package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.mob.HitboxPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * HitboxPart实体的渲染器
 */
public class HitboxPartEntityRenderer extends EntityRenderer<HitboxPart> {

    public HitboxPartEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(HitboxPart entity) {
        // 返回一个透明纹理或null
        return new ResourceLocation("minecraft:textures/empty.png");
    }

    @Override
    public void render(HitboxPart entity, float entityYaw, float partialTicks,
                       com.mojang.blaze3d.vertex.PoseStack poseStack,
                       net.minecraft.client.renderer.MultiBufferSource bufferSource,
                       int packedLight) {
        // 渲染该HitboxPart所属实体的所有受击盒
        if (entity.getBodyPart() != null) {
            HitboxRenderer.getInstance().renderEntityHitboxes(
                    entity.getOwner(),
                    poseStack,
                    bufferSource,
                    partialTicks
            );
        }
    }
}