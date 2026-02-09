package com.catoxide.catoxidesbattlerebuild.server.bodypart;

/**
 * BodyUnit能力接口
 * 定义BodyUnit的特殊能力，如燃烧、冰冻、中毒等
 */
public interface IBodyUnitAbility {
    
    /**
     * 获取能力名称
     */
    String getName();
    
    /**
     * 获取能力类型
     */
    String getType();
    
    /**
     * 初始化能力
     * @param bodyUnit 关联的BodyUnit
     */
    void initialize(IBodyUnit bodyUnit);
    
    /**
     * 重置能力状态
     */
    void reset();
    
    /**
     * 伤害处理前置钩子
     * @param boneName 来源骨骼
     * @param rawDamage 原始伤害
     * @param damageType 伤害类型
     * @return 修改后的伤害值，返回-1表示取消伤害
     */
    float onDamagePre(String boneName, float rawDamage, String damageType);
    
    /**
     * 伤害处理后置钩子
     * @param boneName 来源骨骼
     * @param rawDamage 原始伤害
     * @param actualDamage 实际伤害
     * @param damageType 伤害类型
     */
    void onDamagePost(String boneName, float rawDamage, float actualDamage, String damageType);
    
    /**
     * BodyUnit被击中时触发
     * @param damage 伤害值
     */
    void onHit(float damage);
    
    /**
     * 致命检查钩子
     * @param currentHealth 当前血量
     * @param damage 受到的伤害
     * @return true表示致命，false表示不致命
     */
    boolean onFatalCheck(float currentHealth, float damage);
    
    /**
     * 致命效果触发钩子
     */
    void onFatalEffect();
    
    /**
     * Tick更新钩子
     * @param deltaTick tick间隔
     */
    void onTick(int deltaTick);
    
    /**
     * 检查能力是否激活
     */
    boolean isActive();
    
    /**
     * 设置能力激活状态
     */
    void setActive(boolean active);
}