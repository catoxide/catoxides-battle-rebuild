package example.contentpack.tech;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 科技树节点（数据侧不可变蓝图）。
 *
 * @param page          所属页签 id（{@code techs/tree.json} 的 {@code pages}）
 * @param col/row       页内固定网格坐标 —— 界面按它摆放，不做自动布局
 * @param requirements  解锁条件（数据驱动，见 {@link TechRequirementEngine}）
 * @param visibleWhen   可见条件（空 = 始终可见；不满足时该节点不作为 UI 元素）
 * @param resetAfterTicks 解锁后经过多少 tick 自动回退为未解锁（0 = 不按时间重置）
 * @param resetWhen     满足这些条件时自动回退为未解锁（空 = 不按条件重置）
 * @param unlockTriggers 解锁时写入的自定义触发器
 * @param quest         是否给该节点生成一条个人任务（解锁即完成）
 */
public record TechNode(
        String id,
        String title,
        String desc,
        String page,
        int col,
        int row,
        ResourceLocation icon,
        List<TechRequirement> requirements,
        List<TechCost> costs,
        List<Reward> rewards,
        boolean quest,
        List<TechRequirement> visibleWhen,
        int resetAfterTicks,
        List<TechRequirement> resetWhen,
        List<String> unlockTriggers
) {
    public record Reward(ResourceLocation item, int count) {
    }

    public TechNode {
        requirements = List.copyOf(requirements);
        costs = List.copyOf(costs);
        rewards = List.copyOf(rewards);
        visibleWhen = List.copyOf(visibleWhen);
        resetWhen = List.copyOf(resetWhen);
        unlockTriggers = List.copyOf(unlockTriggers);
    }
}
