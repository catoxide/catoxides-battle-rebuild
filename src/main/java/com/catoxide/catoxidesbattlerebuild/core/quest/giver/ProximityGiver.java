package com.catoxide.catoxidesbattlerebuild.core.quest.giver;

import com.catoxide.catoxidesbattlerebuild.core.quest.IQuestGiver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 内置发放源 {@code proximity_giver}：玩家进入指定坐标半径内即发放。
 *
 * <pre>{@code { "type": "proximity_giver", "center": [0, 64, 0], "radius": 10 }}</pre>
 */
public final class ProximityGiver implements IQuestGiver {

    public static final String TYPE = "proximity_giver";

    private final Vec3 center;
    private final float radius;

    public ProximityGiver(Vec3 center, float radius) {
        this.center = center;
        this.radius = radius;
    }

    /**
     * @throws IllegalArgumentException center 缺失或不是三元数组
     */
    public static ProximityGiver fromJson(JsonObject json) {
        if (!json.has("center")) {
            throw new IllegalArgumentException("missing required field 'center'");
        }
        JsonArray c = json.getAsJsonArray("center");
        if (c.size() != 3) {
            throw new IllegalArgumentException("'center' must be [x, y, z], got " + c.size() + " elements");
        }
        Vec3 center = new Vec3(c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble());
        float radius = json.has("radius") ? json.get("radius").getAsFloat() : 10f;
        return new ProximityGiver(center, radius);
    }

    @Override
    public boolean canGive(ServerPlayer player) {
        return player.position().distanceToSqr(center) <= (double) radius * radius;
    }

    public Vec3 getCenter() {
        return center;
    }

    public float getRadius() {
        return radius;
    }
}
