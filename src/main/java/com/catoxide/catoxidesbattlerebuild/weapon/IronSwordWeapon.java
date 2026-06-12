package com.catoxide.catoxidesbattlerebuild.weapon;

import cn.solarmoon.spark_core.animation.ICustomModelItem;
import cn.solarmoon.spark_core.animation.ItemAnimatable;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.awt.Color;

import static net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion.MOD_ID;

/**
 * 铁剑武器 - 实现 ICustomModelItem 接口以支持 Spark-Core 自定义模型
 */
public class IronSwordWeapon extends Item implements ICustomModelItem {

    public IronSwordWeapon(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return true;
    }

    @Override
    public int getEnchantmentValue() {
        return 14;
    }

    /**
     * 获取物品的渲染实例
     * Spark-Core 调用此方法获取物品的动画控制器
     */
    @Override
    public ItemAnimatable getRenderInstance(ItemStack itemStack, Level level, ItemDisplayContext context) {
        // 创建或获取物品的动画实例
        return createItemAnimatable(itemStack, level, context);
    }

    /**
     * 获取物品的模型索引
     * 指定自定义模型的资源位置
     */
    @Override
    public ModelIndex getModelIndex(ItemStack itemStack, Level level, ItemDisplayContext context) {
        // 从 spark_models/item/catoxidesbattlerebuild/iron_sword.json 加载模型
        ResourceLocation modelLoc = ResourceLocation.fromNamespaceAndPath(
                CatoxidesBattleRebuildConstants.MODID,
            "item/catoxidesbattlerebuild/iron_sword"
        );
        return new ModelIndex("item", modelLoc);
    }

    /**
     * 创建物品动画实例
     */
    @Override
    public ItemAnimatable createItemAnimatable(ItemStack itemStack, Level level, ItemDisplayContext context) {
        // 根据上下文创建不同的动画实例
        IronSwordAnimatable animatable = new IronSwordAnimatable(itemStack, level);
        
        // 设置默认模型
        ModelIndex modelIndex = getModelIndex(itemStack, level, context);
        animatable.getModelController().setModel(modelIndex);
        
        return animatable;
    }

    /**
     * 是否在特定场景使用 2D 模型
     * GUI 和掉落物使用 2D 模型
     */
    @Override
    public boolean use2dModel(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return switch (displayContext) {
            case GUI, GROUND, FIXED -> true;
            default -> false;
        };
    }

    /**
     * 获取渲染偏移
     */
    @Override
    public Vector3f getRenderOffset(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return new Vector3f(0f, 0f, 0f);
    }

    /**
     * 获取渲染旋转
     */
    @Override
    public Vector3f getRenderRotation(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return switch (displayContext) {
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> new Vector3f(0f, -90f, -45f);
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> new Vector3f(0f, 90f, 25f);
            default -> new Vector3f(0f, 0f, 0f);
        };
    }

    /**
     * 获取渲染缩放
     */
    @Override
    public Vector3f getRenderScale(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return new Vector3f(1f, 1f, 1f);
    }

    /**
     * 获取渲染颜色
     */
    @Override
    public Color getColor(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return Color.WHITE;
    }
}
