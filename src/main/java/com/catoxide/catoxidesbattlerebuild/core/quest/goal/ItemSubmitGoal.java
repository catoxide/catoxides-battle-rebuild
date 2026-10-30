package com.catoxide.catoxidesbattlerebuild.core.quest.goal;

import com.catoxide.catoxidesbattlerebuild.core.quest.IQuestGoal;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * 内置目标 {@code item_submit}：背包里持有指定物品 N 个即视为可交付，
 * <b>任务整体完成时</b>才扣除这 N 个。
 *
 * <p>为什么扣在完成回调而不是 {@link IQuestGoal#isComplete}：完成检查每 tick 都跑，
 * 判定必须无副作用且幂等；多目标任务的物品也不能在别的目标还没做完时就被吃掉。
 *
 * <pre>{@code { "type": "item_submit", "item": "minecraft:iron_ingot", "count": 16, "label": "铁锭" }}</pre>
 */
public final class ItemSubmitGoal extends AbstractItemGoal {

    public static final String TYPE = "item_submit";
    private static final String TAG = "QuestItemGoal";

    public ItemSubmitGoal(Item item, ResourceLocation itemId, int required, String label) {
        super(item, itemId, required, label);
    }

    public static ItemSubmitGoal fromJson(JsonObject json) {
        Spec spec = parseSpec(json);
        return new ItemSubmitGoal(spec.item(), spec.itemId(), spec.required(), spec.label());
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public void onQuestCompleted(QuestInstance quest, ServerPlayer player) {
        int consumed = ItemGoalSupport.consume(player, getItem(), getRequired());
        LogManager.serverInfo(TAG, "Submitted %d/%d x %s for quest '%s' (player=%s)",
                consumed, getRequired(), getItemId(), quest.getDefinition().id(), player.getUUID());
    }
}
