package com.catoxide.catoxidesbattlerebuild.core.mob;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 射线追踪行为组件（第一版：基础视线检测）
 * <p>实体定义 JSON：{ "type": "raycast", "range": 12.0 }
 * <p>第一版实现：定期检测视野半径内最近的玩家（用于警觉/索敌状态判定）。
 * 移动结构（sable sub-level）坐标转换将在 sable 防腐层接入后增强。
 */
public class RaycastBehavior implements IMobBehavior {

    private static final String TAG = "RaycastBehavior";

    private final double range;
    private long lastScanTick = 0;
    private LivingEntity currentTarget = null;

    public RaycastBehavior(double range) {
        this.range = range;
    }

    @Override
    public void tick(DataDrivenMob mob) {
        long gameTime = mob.level().getGameTime();
        // 每 20 tick 扫描一次，避免每 tick 全量检测的开销
        if (gameTime - lastScanTick < 20) {
            return;
        }
        lastScanTick = gameTime;
        currentTarget = null;

        if (mob.level().isClientSide) {
            return;
        }

        AABB box = mob.getBoundingBox().inflate(range);
        List<Player> players = mob.level().getEntitiesOfClass(Player.class, box);
        double bestDist = Double.MAX_VALUE;
        for (Player player : players) {
            if (player.isCreative() || player.isSpectator()) {
                continue;
            }
            Vec3 eyePos = mob.getEyePosition(1.0F);
            Vec3 targetPos = player.getEyePosition(1.0F);
            double dist = eyePos.distanceToSqr(targetPos);
            if (dist <= range * range && dist < bestDist && mob.hasLineOfSight(player)) {
                bestDist = dist;
                currentTarget = player;
            }
        }
    }

    /** 当前射线追踪目标（可能为 null） */
    public LivingEntity currentTarget() {
        return currentTarget;
    }

    public double range() {
        return range;
    }

    /** 工厂 */
    public static final class Factory implements IBehaviorFactory {
        @Override
        public String type() { return "raycast"; }

        @Override
        public IMobBehavior create(JsonObject config) {
            double range = config.has("range") ? config.get("range").getAsDouble() : 12.0;
            return new RaycastBehavior(range);
        }
    }
}
