package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import net.minecraft.resources.ResourceLocation;

/**
 * 跨内容包「能力」token —— 依赖倒置的 <b>标识 + 接口类型</b>。
 *
 * <h3>它解决什么问题</h3>
 * 内容包之间需要互相使用功能，但 <b>绝不能共享类空间</b>：每个包由独立的
 * {@link ChildFirstClassLoader} 加载，若包 A 直接 import 包 B 的实现类，A 会加载到
 * B 的一份<b>私有副本</b>，B 自己的静态状态随即出现两份且静默发散。
 *
 * <p>正确做法是<b>依赖倒置</b>：
 * <ul>
 *   <li>接口（如 {@code AmmoTypeProvider}）与 token（本类实例）住在 <b>core / api</b>，
 *       即主 mod 包名下 —— 该前缀在 {@link ChildFirstClassLoader} 中 parent-only，
 *       因此所有包看到的是<b>同一个 Class 对象</b>，接口转换必然成功。</li>
 *   <li>实现由包 B 提供（{@link ContentPackContext#provide}），实现类只在 B 的 loader 中加载。</li>
 *   <li>包 A 只经由接口调用它，<b>从不 import B 的实现类</b>，因此拿不到也不需要副本。</li>
 * </ul>
 *
 * <h3>标准用法</h3>
 * <pre>{@code
 * // ── core / api：契约 + token（必须住在主 mod 包名下）──
 * public interface AmmoTypeProvider { int ammoOf(ItemStack stack); }
 *
 * public final class GunCaps {
 *     public static final Capability<AmmoTypeProvider> AMMO_TYPES =
 *             Capability.of("gun", "ammo_types", AmmoTypeProvider.class);
 * }
 *
 * // ── 包 B：提供 ──
 * public void register(ContentPackContext ctx) {
 *     ctx.provide(GunCaps.AMMO_TYPES, new MyAmmoProvider());
 * }
 *
 * // ── 包 A：消费（MANIFEST 中声明依赖 gun-framework 以保证顺序）──
 * public void register(ContentPackContext ctx) {
 *     ctx.capability(GunCaps.AMMO_TYPES).ifPresent(ammo -> registerAmmoCompat(ctx, ammo));
 * }
 * }</pre>
 *
 * <h3>相等性（重要）</h3>
 * 两个 token 相等 <b>当且仅当 id 相同</b> —— {@link Class} 类型不参与相等性判断，
 * 而是作为<b>被校验的属性</b>。这样当某个包用错误的类型定义同名 token 时，
 * 注册表会报出明确的类型冲突错误，而不是静默产生第二个槽位。
 *
 * @param <T> 能力接口类型（必须定义在 core / api 中，见类文档）
 */
public final class Capability<T> {

    private final ResourceLocation id;
    private final Class<T> type;

    private Capability(ResourceLocation id, Class<T> type) {
        this.id = id;
        this.type = type;
    }

    /**
     * 创建能力 token。
     *
     * @param id   全局唯一标识（建议用 {领域}:{能力} 形式，如 {@code gun:ammo_types}）
     * @param type 能力接口的 Class；<b>必须定义在主 mod 包名下</b>，否则跨包类型不一致
     */
    public static <T> Capability<T> of(ResourceLocation id, Class<T> type) {
        if (id == null) {
            throw new IllegalArgumentException("Capability id must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("Capability type must not be null for " + id);
        }
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("Capability type must be an interface or reference type, not primitive: " + id);
        }
        return new Capability<>(id, type);
    }

    /** 便捷重载：{@code Capability.of("gun", "ammo_types", AmmoTypeProvider.class)} */
    public static <T> Capability<T> of(String namespace, String path, Class<T> type) {
        return of(ResourceLocation.fromNamespaceAndPath(namespace, path), type);
    }

    public ResourceLocation id() {
        return id;
    }

    public Class<T> type() {
        return type;
    }

    /** 相等性只看 id —— 类型冲突由 {@link CapabilityRegistry} 校验并报错。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Capability<?> other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id + "[" + type.getName() + "]";
    }
}
