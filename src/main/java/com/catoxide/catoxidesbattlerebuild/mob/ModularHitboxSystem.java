package com.catoxide.catoxidesbattlerebuild.mob;

import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModularHitboxSystem {
    private final ModularZombie parent;
    private final Map<String, HitboxPart> hitboxParts = new HashMap<>();
    private final List<String> bodyParts = Arrays.asList("head", "torso", "arm_left", "arm_right", "leg_left", "leg_right");

    public ModularHitboxSystem(ModularZombie parent) {
        this.parent = parent;
        initializeHitboxes();
    }

    private void initializeHitboxes() {
        for (String part : bodyParts) {
            // 创建 HitboxPart 并初始化
            HitboxPart hitbox = new HitboxPart(ModEntities.HITBOX_PART.get(), parent.level());
            hitbox.initialize(parent, part);
            parent.level().addFreshEntity(hitbox);
            hitboxParts.put(part, hitbox);
        }
    }

    public void updateHitboxPositions() {
        // 暂时留空，后续实现位置同步逻辑
    }

    public void cleanup() {
        for (HitboxPart hitbox : hitboxParts.values()) {
            if (hitbox != null && hitbox.isAlive()) {
                hitbox.discard();
            }
        }
        hitboxParts.clear();
    }
}