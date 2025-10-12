// 1. 基础使用（依赖默认配置）
DamageComponent fireball = new DamageComponent(DamageType.FIRE, 15.0f);

// 2. 显式指定穿透
DamageComponent armorPiercingBullet = new DamageComponent(
DamageType.PIERCING, 12.0f, PenetrationLevel.ARMOR_PIERCING
);

// 3. 武器覆盖
LongswordWeapon sword = new LongswordWeapon();
DamageComponent swordSlash = sword.createDamageComponent(DamageType.SLASHING, 10.0f);

// 4. 开发时动态调整
// 在游戏内通过命令或调试界面调用：
DevPenetrationTools.setDamagePenetration(DamageType.FIRE, PenetrationLevel.HEAVY);
DevPenetrationTools.testPenetration(DamageType.FIRE, ArmorClass.HEAVY);