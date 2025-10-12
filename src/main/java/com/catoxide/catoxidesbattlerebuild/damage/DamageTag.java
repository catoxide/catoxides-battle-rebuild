package com.catoxide.catoxidesbattlerebuild.damage;

public enum DamageTag {
    BYPASS_SHIELD,      // 穿透盾
    CRITICAL,           // 暴击
    BYPASS_ARMOR,       // 无视护甲
    IS_EXPLOSION,       // 爆炸
    DAMAGES_HELMET,     // 头部伤害
//    PANIC_CAUSES,       //

    AREA_OF_EFFECT,     // 范围伤害
    PROJECTILE,         // 投射物
    MELEE,              // 近战
    RANGED,             // 远程
    MAGIC,              // 魔法
    DOT,                // 持续伤害
    IGNORES_INVULNERABILITY, // 忽略无敌
    CAN_BE_BLOCKED,     // 可被格挡
    CAN_BE_DODGED,      // 可被闪避
    LIFESTEAL,          // 生命偷取
    MANA_BURN,          // 法力燃烧
    STUN,               // 附带眩晕
    IGNORES_KNOCKBACK           // 附带击退
}
