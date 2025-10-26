package com.catoxide.catoxidesbattlerebuild.mob;

import java.util.List; // 添加导入

public class BodyPartHealthSystem {
    private final ModularZombie parent;
    private final BodyPartManager partManager;

    public BodyPartHealthSystem(ModularZombie parent, BodyPartManager partManager) {
        this.parent = parent;
        this.partManager = partManager;
    }

    // 处理部位受到的伤害
    public void onPartHit(String partName, float damage) {
        BodyPart part = partManager.getBodyPart(partName);
        if (part == null || part.isDestroyed()) return;

        // 记录部位被破坏前的状态
        boolean wasIntact = !part.isDestroyed();

        // 处理伤害，获取对主体的伤害值
        float damageToMain = part.takeDamage(damage);

        // 检查部位是否刚刚被破坏
        boolean justDestroyed = wasIntact && part.isDestroyed();

        // 对主体造成伤害
        if (damageToMain > 0) {
            // 这里需要实现对主体的伤害逻辑
            applyDamageToMainEntity(damageToMain);
        }

        // 如果部位刚刚被破坏，触发被破坏事件
        if (justDestroyed) {
            onPartDestroyed(partName);
        }

        // 检查是否所有部位都被破坏
        if (isAllPartsDestroyed()) {
            parent.kill();
        }
    }

    // 对主体造成伤害的方法
    private void applyDamageToMainEntity(float damage) {
        // 这里需要实现具体的伤害逻辑
        // 例如：parent.hurt(damageSource, damage);
        System.out.println("对主体造成伤害: " + damage);
    }

    // 部位被摧毁的逻辑
    private void onPartDestroyed(String partName) {
        HitboxPart singleHitbox = partManager.getHitboxEntity(partName);
        if (singleHitbox != null) {
            singleHitbox.discard();
        }

        List<HitboxPart> cluster = partManager.getPreciseHitboxCluster(partName);
        if (cluster != null) {
            cluster.forEach(HitboxPart::discard);
        }

        applyPartDestroyedEffects(partName);
    }

    // 应用部位摧毁效果
    private void applyPartDestroyedEffects(String partName) {
        switch (partName) {
            case "leg_left":
            case "leg_right":
                // 使用 triggerAnim 而不是 setCurrentAnimation
                parent.triggerAnim("controller", "animation.zombie.limp");
                break;
            case "arm_left":
            case "arm_right":
                // 降低攻击力或改变攻击动画
                break;
            case "head":
                parent.kill();
                break;
        }
    }

    // 检查是否所有部位都被摧毁
    private boolean isAllPartsDestroyed() {
        return partManager.getBodyParts().values().stream().allMatch(BodyPart::isDestroyed);
    }

    // 精确射线检测
    public String rayTracePreciseParts(net.minecraft.world.phys.Vec3 start, net.minecraft.world.phys.Vec3 end) {
        return partManager.rayTracePreciseParts(start, end);
    }
}