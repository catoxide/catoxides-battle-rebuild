package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

// 命中部位数据包
public class PartHitPacket {
    private final int zombieId;
    private final String partName;
    private final float damage;
    private final long hitTime;

    public PartHitPacket(int zombieId, String partName, float damage) {
        this.zombieId = zombieId;
        this.partName = partName;
        this.damage = damage;
        this.hitTime = System.currentTimeMillis();
    }

    // 处理数据包（在客户端）
    @OnlyIn(Dist.CLIENT)
    public void handle(ClientPacketListener listener) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Entity entity = mc.level.getEntity(zombieId);
        if (entity instanceof ModularZombie) {
            ModularZombie zombie = (ModularZombie) entity;

            // 更新客户端的视觉效果
            zombie.getBodyPartManager().markPartAsRecentlyHit(partName);

            // 显示粒子效果或声音
            spawnHitParticles(zombie, partName);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void spawnHitParticles(ModularZombie zombie, String partName) {
        // 在命中部位生成粒子效果
        Level level = zombie.level();
        Vec3 partPos = calculatePartPosition(zombie, partName);

        if (partPos != null) {
            level.addParticle(ParticleTypes.CRIT,
                    partPos.x, partPos.y, partPos.z,
                    0, 0, 0);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private Vec3 calculatePartPosition(ModularZombie zombie, String partName) {
        // 计算部位的大致位置
        // 这里可以根据骨骼变换计算
        return zombie.position().add(0, 1, 0); // 临时实现
    }
}