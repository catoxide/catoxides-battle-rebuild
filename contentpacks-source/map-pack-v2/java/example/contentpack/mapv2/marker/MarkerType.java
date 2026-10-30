package example.contentpack.mapv2.marker;

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
     * 地点标记：PING / STICKY。
     * <p>PING 与 STICKY 已经覆盖了原先 MAP 的语义，所以不再单独保留 MAP。
     */
    public boolean waypoint() {
        return this == PING || this == STICKY;
    }

    /**
     * 临时地点标记：世界 / 罗盘 / 地图上画小三角形，没有名字、图标不可编辑。
     * <p>永久地点标记（STICKY）必须绑定一个真实图标，用它代替三角形当位置标志。
     */
    public boolean temporaryWaypoint() {
        return this == PING;
    }

    /** 可编辑（名字 / 坐标 / 图标 / 呈现方式）的只有永久地点标记。 */
    public boolean editable() {
        return this == STICKY;
    }
}
