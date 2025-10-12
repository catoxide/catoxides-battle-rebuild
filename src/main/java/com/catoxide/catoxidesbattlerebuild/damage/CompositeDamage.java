package com.catoxide.catoxidesbattlerebuild.damage;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class CompositeDamage {
    private final List<DamageComponent> components = new ArrayList<>();
    private final DamageSource originalSource; // 原始伤害来源
    private final LivingEntity attacker; // 攻击者
    private final LivingEntity target; // 目标
    private final Vec3 impactPosition;// 命中位置

    // 伤害标签系统（用于特殊效果判断）
    private final Set<DamageTag> tags = new HashSet<>();

    // 构造函数
    public CompositeDamage(DamageSource source, LivingEntity attacker, LivingEntity target) {
        this.originalSource = source;
        this.attacker = attacker;
        this.target = target;
        this.impactPosition = target.position();
    }

    // 添加伤害组件
    public void addComponent(DamageComponent component) {
        components.add(component);
    }

    // 获取总伤害（计算前）
    public float getTotalBaseDamage() {
        return components.stream()
                .map(DamageComponent::getBaseAmount)
                .reduce(0f, Float::sum);
    }

    // 获取特定类型的伤害组件
    public List<DamageComponent> getComponentsByType(DamageType type) {
        return components.stream()
                .filter(comp -> comp.getType() == type)
                .collect(Collectors.toList());
    }

    // 添加标签
    public void addTag(DamageTag tag) {
        tags.add(tag);
    }

    public boolean hasTag(DamageTag tag) {
        return tags.contains(tag);
    }

    // Getter方法
    public List<DamageComponent> getComponents() { return components; }
    public DamageSource getOriginalSource() { return originalSource; }
    public LivingEntity getAttacker() { return attacker; }
    public LivingEntity getTarget() { return target; }
    public Vec3 getImpactPosition() { return impactPosition; }
    public Set<DamageTag> getTags() { return tags; }
}