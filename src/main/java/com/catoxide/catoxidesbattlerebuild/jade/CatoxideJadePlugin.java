package com.catoxide.catoxidesbattlerebuild.jade;

import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import snownee.jade.api.Accessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade 插件入口（标准显示接入点）。
 * <p>通过 {@code @WailaPlugin} 注解被 Jade 自动发现（NeoForge 无需 entrypoints）。
 * <p>Jade 未安装时本类不会被加载——主 mod 不强依赖 Jade（compileOnly）。
 * <p>职责：
 * <ul>
 *   <li>把实体骨骼部位血量通过 Jade 显示（服务端数据同步 + 客户端 tooltip）</li>
 *   <li>自定义射线拾取：用**骨骼（动画外形）球体**代替原版碰撞箱判定"准星对准了谁"——
 *       准星对到模型外形（如手臂伸出碰撞箱外）也能显示该实体的信息</li>
 * </ul>
 */
@WailaPlugin
public class CatoxideJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerEntityDataProvider(BoneHealthProvider.INSTANCE, AnimatedMob.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(BoneHealthProvider.INSTANCE, AnimatedMob.class);
        // 骨骼外形拾取：低 priority = 早执行（先于 Jade 默认拾取结果生效）
        registration.addRayTraceCallback(-100, (hitResult, accessor, originalAccessor) ->
                overridePickTarget(registration, hitResult, accessor));
    }

    /**
     * 自定义射线拾取：Jade 默认用原版碰撞箱判定拾取目标；
     * 本回调在默认未命中 AnimatedMob 时，用玩家视线 vs 骨骼球体（动画外形）重新判定，
     * 命中则构造该实体的 {@link EntityAccessor} 让 Jade 显示。
     */
    private static Accessor<?> overridePickTarget(
            IWailaClientRegistration registration,
            HitResult hitResult,
            Accessor<?> accessor) {
        // 默认已命中我们支持的实体（碰撞箱内）→ 保持默认
        if (accessor instanceof EntityAccessor ea && ea.getEntity() instanceof AnimatedMob<?>) {
            return accessor;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return accessor;
        }
        BoneHealthProvider.BoneOBBHit pick = BoneHealthProvider.raycastNearestOBB(mc.player);
        if (pick == null) {
            return accessor;
        }
        EntityHitResult ehr = new EntityHitResult(pick.mob(), pick.hitPoint());
        return registration.entityAccessor()
                .level(mc.player.level())
                .player(mc.player)
                .entity(pick.mob())
                .hit(ehr)
                .build();
    }
}
