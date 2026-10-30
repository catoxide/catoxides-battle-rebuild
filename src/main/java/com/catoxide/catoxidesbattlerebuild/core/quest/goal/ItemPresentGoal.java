package com.catoxide.catoxidesbattlerebuild.core.quest.goal;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * 内置目标 {@code item_present}：背包里持有指定物品 N 个即完成。
 * <p>纯检测，不扣物品。用于「收集 / 携带 / 检查物品」类任务。
 *
 * <pre>{@code { "type": "item_present", "item": "minecraft:apple", "count": 8, "label": "苹果" }}</pre>
 */
public final class ItemPresentGoal extends AbstractItemGoal {

    public static final String TYPE = "item_present";

    public ItemPresentGoal(Item item, ResourceLocation itemId, int required, String label) {
        super(item, itemId, required, label);
    }

    public static ItemPresentGoal fromJson(JsonObject json) {
        Spec spec = parseSpec(json);
        return new ItemPresentGoal(spec.item(), spec.itemId(), spec.required(), spec.label());
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
