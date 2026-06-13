package com.catoxide.catoxidesbattlerebuild.client.renderer;

import cn.solarmoon.spark_core.animation.renderer.GeoItemRenderer;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.catoxide.catoxidesbattlerebuild.weapon.IronSwordAnimatable;
import com.catoxide.catoxidesbattlerebuild.weapon.IronSwordWeapon;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * 铁剑物品渲染器
 * 负责渲染带有动画的铁剑
 */
public class IronSwordRenderer extends GeoItemRenderer {

    public IronSwordRenderer() {
        LogManager.clientInfo("IronSwordRenderer", "IronSwordRenderer initialized");
    }

    @Override
    public void renderByItem(ItemStack itemStack, ItemDisplayContext displayContext, 
                             PoseStack poseStack, MultiBufferSource buffer, 
                             int packedLight, int packedOverlay) {
        // 检查是否是铁剑
        if (itemStack.getItem() instanceof IronSwordWeapon) {
            LogManager.clientDebug("IronSwordRenderer", "Rendering iron sword with display context: {}", displayContext);
            
            // 获取或创建动画实例
            IronSwordAnimatable animatable = getAnimatable(itemStack);
            
            if (animatable != null) {
                // 设置持有者
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    animatable.setHolder(player);
                }
                
                // 渲染物品
                super.renderByItem(itemStack, displayContext, poseStack, buffer, packedLight, packedOverlay);
                
                LogManager.clientDebug("IronSwordRenderer", "Successfully rendered iron sword");
            } else {
                LogManager.clientWarn("IronSwordRenderer", "Failed to get animatable for iron sword");
                // 回退到默认渲染
                super.renderByItem(itemStack, displayContext, poseStack, buffer, packedLight, packedOverlay);
            }
        } else {
            // 不是铁剑，使用默认渲染
            super.renderByItem(itemStack, displayContext, poseStack, buffer, packedLight, packedOverlay);
        }
    }

    /**
     * 获取铁剑的动画实例
     */
    private IronSwordAnimatable getAnimatable(ItemStack itemStack) {
        try {
            if (itemStack.getItem() instanceof IronSwordWeapon weapon) {
                return (IronSwordAnimatable) weapon.getRenderInstance(
                    itemStack, 
                    Minecraft.getInstance().level, 
                    ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                );
            }
        } catch (Exception e) {
            LogManager.clientError("IronSwordRenderer", "Failed to get animatable", e);
        }
        return null;
    }
}
