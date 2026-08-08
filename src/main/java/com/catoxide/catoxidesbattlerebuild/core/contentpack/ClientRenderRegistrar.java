package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * 客户端渲染器注册器
 * <p>由主 mod 在 {@code EntityRenderersEvent.RegisterRenderers} 中构造并传给
 * {@link ContentPack#registerClientRenderers(ClientRenderRegistrar)}；
 * pack 在此注册自己实体的渲染器，主 mod 无需知道 pack 的具体类（去除 instanceof 耦合）。
 *
 * <p>仅应在 Dist.CLIENT 下调用（接口签名含客户端类，运行时服务端不会触及）。
 */
public interface ClientRenderRegistrar {

    /**
     * 注册一个实体类型的渲染器。
     * <p>使用 raw 类型避免通配符泛型方法引用的推断问题；
     * 调用方（pack）传入自己持有的 DeferredHolder 与渲染器工厂。
     *
     * @param entityType 实体类型 DeferredHolder（pack 在 register() 阶段持有）
     * @param provider   渲染器工厂（EntityRendererProvider，raw）
     */
    @SuppressWarnings("rawtypes")
    void register(DeferredHolder entityType, EntityRendererProvider provider);
}
