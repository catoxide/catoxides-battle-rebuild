package com.catoxide.catoxidesbattlerebuild.core.quest.goal;

import com.catoxide.catoxidesbattlerebuild.core.quest.IQuestGoal;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 物品类目标的公共部分：持有物品、所需数量、进度标签。
 * <p>完成判定是纯查询（背包里够数即完成），不会动玩家物品——
 * 扣除只发生在 {@code item_submit} 的任务完成回调里。
 */
public abstract class AbstractItemGoal implements IQuestGoal {

    /** JSON 解析结果（工厂内部用） */
    public record Spec(Item item, ResourceLocation itemId, int required, String label) {
    }

    private final Item item;
    private final ResourceLocation itemId;
    private final int required;
    private final String label;

    protected AbstractItemGoal(Item item, ResourceLocation itemId, int required, String label) {
        this.item = item;
        this.itemId = itemId;
        this.required = required;
        this.label = label;
    }

    /**
     * 按 JSON 字段解析：{@code item}（必填）、{@code count}（默认 1）、{@code label}（默认物品路径）。
     *
     * @throws IllegalArgumentException 物品字段缺失 / id 非法 / 物品未注册
     */
    protected static Spec parseSpec(JsonObject json) {
        if (!json.has("item")) {
            throw new IllegalArgumentException("missing required field 'item'");
        }
        String rawItem = json.get("item").getAsString();
        Item item = ItemGoalSupport.resolve(rawItem);
        if (item == null) {
            throw new IllegalArgumentException("unknown item '" + rawItem + "' (not registered)");
        }
        ResourceLocation itemId = ItemGoalSupport.parseId(rawItem);
        int required = json.has("count") ? Math.max(1, json.get("count").getAsInt()) : 1;
        String label = json.has("label") && !json.get("label").getAsString().isBlank()
                ? json.get("label").getAsString()
                : itemId.getPath();
        return new Spec(item, itemId, required, label);
    }

    @Override
    public boolean isComplete(QuestInstance quest, ServerPlayer player) {
        return ItemGoalSupport.count(player, item) >= required;
    }

    @Override
    public Map<String, Float> getProgress(QuestInstance quest, ServerPlayer player) {
        Map<String, Float> progress = new LinkedHashMap<>();
        progress.put(label, (float) ItemGoalSupport.count(player, item));
        progress.put(label + "_need", (float) required);
        return progress;
    }

    public Item getItem() {
        return item;
    }

    public ResourceLocation getItemId() {
        return itemId;
    }

    public int getRequired() {
        return required;
    }

    public String getLabel() {
        return label;
    }
}
