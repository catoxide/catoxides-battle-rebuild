package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.core.projectile.CustomProjectileEntity;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * CustomProjectileEntity 的基础渲染器。
 * <p>
 * 当前只渲染阴影。投射物本体由 Spark 模型层渲染（需配置 spark_models）。
 * 后续可替换为完整模型渲染或粒子效果。
 */
public class CustomProjectileRenderer extends EntityRenderer<CustomProjectileEntity> {

    public CustomProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.2f;
        this.shadowStrength = 0.3f;
        LogManager.clientStartup("CustomProjectileRenderer", "Initialized");
    }

    @Override
    public void render(CustomProjectileEntity entity, float yaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        poseStack.translate(0, -0.25, 0);
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(CustomProjectileEntity entity) {
        return ResourceLocation.withDefaultNamespace("textures/misc/border.png");
    }

    @Override
    public boolean shouldRender(CustomProjectileEntity entity, Frustum camera,
                                double camX, double camY, double camZ) {
        return entity.distanceToSqr(camX, camY, camZ) < 1024.0;
    }
}
