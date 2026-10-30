package example.contentpack.hudlayout.layout;

import org.jetbrains.annotations.Nullable;

/**
 * 一组 HUD 允许的<b>移动轴</b>——拖动约束，不是位置。
 *
 * <p>锁住的轴恒为 {@code 0}，也就是元素自己的<b>原位置</b>（偏移是相对原版位置量的）。
 * 所以「只能上下移动」= {@link #VERTICAL}：横偏移回到 0，纵偏移随便拖。
 *
 * <p>存在 {@code hud-layout.json} 而不是配置里：这是玩家摆位的一部分，和偏移同源同命，
 * 而且原版的 hotbar 组并没有自己的配置可写。
 */
public enum MoveAxis {

    /** 自由拖动（默认，等于没有约束）。 */
    FREE("free"),
    /** 只能左右移动：纵偏移锁回原位置。 */
    HORIZONTAL("horizontal"),
    /** 只能上下移动：横偏移锁回原位置。 */
    VERTICAL("vertical");

    private final String jsonName;

    MoveAxis(String jsonName) {
        this.jsonName = jsonName;
    }

    /** 写进 {@code hud-layout.json} 的值。 */
    public String jsonName() {
        return jsonName;
    }

    public int limitX(int x) {
        return this == VERTICAL ? 0 : x;
    }

    public int limitY(int y) {
        return this == HORIZONTAL ? 0 : y;
    }

    /** 编辑屏右键菜单循环用的下一个档位。 */
    public MoveAxis next() {
        return switch (this) {
            case FREE -> HORIZONTAL;
            case HORIZONTAL -> VERTICAL;
            case VERTICAL -> FREE;
        };
    }

    public boolean isConstrained() {
        return this != FREE;
    }

    /** 菜单显示名键；显示「当前是什么」而不是「点了会变成什么」，读起来才不会反过来。 */
    public String translationKey() {
        return "hudlayout.axis." + jsonName;
    }

    /**
     * 解析 JSON 里的轴名。
     *
     * @return {@code null} 表示不认识（调用方按 {@link #FREE} 处理并告警，不抛异常——
     *         手改配置文件写错一个词不该让整份布局失效）
     */
    @Nullable
    public static MoveAxis fromJson(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (MoveAxis axis : values()) {
            if (axis.jsonName.equalsIgnoreCase(name)) {
                return axis;
            }
        }
        return null;
    }
}
