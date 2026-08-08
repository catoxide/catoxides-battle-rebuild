package com.catoxide.catoxidesbattlerebuild.core.mob;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 目标行为组件
 * <p>实体定义 JSON：{ "type": "ai", "goals": ["hunt_player", "random_stroll", "look_around"] }
 * <p>内置 goal：hunt_player（主动攻击玩家）/ random_stroll（闲逛）/ look_around（观望）。
 * 插件可通过注册自定义 {@link IBehaviorFactory} 扩展新 goal 类型。
 */
public class AiBehavior implements IMobBehavior {

    private final List<String> goals;

    public AiBehavior(List<String> goals) {
        this.goals = goals;
    }

    @Override
    public void registerGoals(DataDrivenMob mob) {
        // 目标选择（targetSelector）
        if (goals.contains("hunt_player")) {
            mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, Player.class, true));
        }
        if (goals.contains("hurt_by_target")) {
            mob.targetSelector.addGoal(1, new HurtByTargetGoal(mob));
        }

        // 移动/行为（goalSelector）
        if (goals.contains("random_stroll")) {
            mob.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(mob, 1.0));
        }
        if (goals.contains("look_at_player")) {
            mob.goalSelector.addGoal(7, new LookAtPlayerGoal(mob, Player.class, 8.0F));
        }
        if (goals.contains("look_around")) {
            mob.goalSelector.addGoal(8, new RandomLookAroundGoal(mob));
        }
    }

    /** 工厂 */
    public static final class Factory implements IBehaviorFactory {
        @Override
        public String type() { return "ai"; }

        @Override
        public IMobBehavior create(JsonObject config) {
            List<String> goalList = new ArrayList<>();
            if (config.has("goals")) {
                JsonArray arr = config.getAsJsonArray("goals");
                for (var el : arr) {
                    goalList.add(el.getAsString());
                }
            }
            return new AiBehavior(goalList);
        }
    }
}
