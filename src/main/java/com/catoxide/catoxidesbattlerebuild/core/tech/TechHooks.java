package com.catoxide.catoxidesbattlerebuild.core.tech;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.Capability;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * 科技树 ↔ 其它内容包的扩展钩子（基座只放契约，不放置科技树逻辑）。
 *
 * <ul>
 *   <li>{@link TriggerEvent}：任意包在游戏事件里 {@code NeoForge.EVENT_BUS.post(...)}，
 *       科技树按 {@link TriggerEvent#trigger} 名记录「该玩家已触发」，供
 *       {@code {"type":"trigger","name":"..."}} 条件使用。</li>
 *   <li>{@link RequirementExtension}：自定义条件类型。科技树包通过
 *       {@link #REQUIREMENTS} capability 提供注册器，其它包在 {@code register()} 里
 *       消费并注册自己的条件类型。</li>
 * </ul>
 */
public final class TechHooks {

    private TechHooks() {
    }

    /**
     * 自定义触发器事件。
     * <p>示例：{@code NeoForge.EVENT_BUS.post(new TechHooks.TriggerEvent(player, "found_ruins"));}
     */
    public static final class TriggerEvent extends Event {
        public final ServerPlayer player;
        public final String trigger;

        public TriggerEvent(ServerPlayer player, String trigger) {
            this.player = player;
            this.trigger = trigger;
        }
    }

    /**
     * 自定义条件类型（包实现；类型 id 全局唯一，建议 {@code <pack>:<name>}）。
     */
    public interface RequirementExtension {

        String type();

        /** 条件是否满足。只在服务端线程调用。 */
        boolean satisfied(ServerPlayer player, JsonObject params);

        /** 给玩家看的条件说明（UI 用）。 */
        String describe(JsonObject params);
    }

    /** 条件类型注册器（由科技树包通过 {@link #REQUIREMENTS} 提供）。 */
    public interface RequirementRegistrar {
        void register(RequirementExtension extension);
    }

    /** capability token：其它包消费后注册自定义条件类型。 */
    public static final Capability<RequirementRegistrar> REQUIREMENTS =
            Capability.of("tech", "requirements", RequirementRegistrar.class);
}
