package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;

import java.util.List;

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

        // 实际实现可能类似：
        // parent.hurt(parent.damageSources().generic(), damage);
    }

    // 修正的部位摧毁逻辑
    private void onPartDestroyed(String partName) {
        System.out.println("部位被摧毁: " + partName);

        // 清理精确碰撞箱集群
        List<HitboxPart> cluster = partManager.getPreciseHitboxCluster(partName);
        if (cluster != null) {
            cluster.forEach(hitbox -> {
                if (hitbox != null) {
                    hitbox.discard();
                }
            });
            // 从映射中移除该集群
            // partManager.removeHitboxCluster(partName); // 如果实现了这个方法
        }

        // 应用部位摧毁效果
        applyPartDestroyedEffects(partName);

        // 同步状态到客户端
        syncPartDestroyedState(partName);
    }

    // 应用部位摧毁效果
    private void applyPartDestroyedEffects(String partName) {
        switch (partName) {
            case "leg_left":
            case "leg_right":
                // 腿部摧毁效果：移动速度降低，播放跛行动画
                System.out.println("腿部被摧毁，降低移动速度");
                // parent.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.1);
                // 使用 triggerAnim 播放跛行动画
                parent.triggerAnim("controller", "animation.zombie.limp");
                break;

            case "arm_left":
            case "arm_right":
                // 手臂摧毁效果：攻击力降低
                System.out.println("手臂被摧毁，降低攻击力");
                // parent.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(2.0);
                break;

            case "head":
                // 头部摧毁效果：立即死亡
                System.out.println("头部被摧毁，立即死亡");
                parent.kill();
                break;

            case "torso":
                // 躯干摧毁效果：大幅降低生命值
                System.out.println("躯干被摧毁，大幅降低生命值");
                parent.hurt(parent.damageSources().generic(), 50.0f);
                break;
        }
    }

    // 同步部位摧毁状态到客户端
    private void syncPartDestroyedState(String partName) {
        // 这里可以实现网络同步逻辑
        // 例如发送数据包通知客户端该部位已被摧毁
//TODO        partManager.markPartAsDestroyed(partName);

    }

    // 检查是否所有部位都被摧毁
    private boolean isAllPartsDestroyed() {
        return partManager.getBodyParts().values().stream()
                .allMatch(BodyPart::isDestroyed);
    }

    // 精确射线检测
    public String rayTracePreciseParts(net.minecraft.world.phys.Vec3 start, net.minecraft.world.phys.Vec3 end) {
        return partManager.rayTracePreciseParts(start, end);
    }

    // 获取部位健康状态信息（用于调试或UI显示）
    public String getPartHealthInfo() {
        StringBuilder sb = new StringBuilder();
        for (BodyPart part : partManager.getBodyParts().values()) {
            sb.append(part.getPartName())
                    .append(": ")
                    .append(part.getCurrentHealth())
                    .append("/")
                    .append(part.getMaxHealth())
                    .append(part.isDestroyed() ? " [DESTROYED]" : "")
                    .append("\n");
        }
        return sb.toString();
    }
}