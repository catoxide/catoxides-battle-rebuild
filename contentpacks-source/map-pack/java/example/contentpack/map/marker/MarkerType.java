package example.contentpack.map.marker;

public enum MarkerType {

    /** 快速标记：时间过期，可重复标记刷新。 */
    PING,
    /** 稳定地点标记：永久。 */
    STICKY,
    /** 死亡点：owner 靠近后消失。 */
    DEATH,
    /** 实体标记：实体消失后失效。 */
    ENTITY,
    /** 身上的物品标记：绑定槽位，物品离开后失效。 */
    ITEM,
    /** 容器标记：绑定容器方块。 */
    CONTAINER;

    public static MarkerType byName(String name) {
        for (MarkerType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return PING;
    }

    /** 时间型标记（有 TTL，重复标记刷新）。永久只有 STICKY / DEATH。 */
    public boolean timed() {
        return this == PING || this == ITEM || this == ENTITY || this == CONTAINER;
    }

    /**
     * 地点标记：世界 / 罗盘 / 地图上都画成「指向位置的小三角形」。
     * <p>PING 与 STICKY 已经覆盖了原先 MAP 的语义，所以不再单独保留 MAP。
     */
    public boolean waypoint() {
        return this == PING || this == STICKY;
    }
}
