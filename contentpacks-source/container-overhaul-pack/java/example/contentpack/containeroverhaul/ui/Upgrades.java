package example.contentpack.containeroverhaul.ui;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 每个玩家一份常驻的 4 格升级容器（解锁数的唯一来源）。
 *
 * <p>为什么不用 {@link PanelStorage} 直接当容器：{@code Player.inventoryMenu} 在 {@code Player}
 * 构造器里就建好了，那时 {@code ServerPlayer.server} 还没赋值，{@code PanelStorage.containerFor}
 * 会因为拿不到 server 抛错。所以这里先给每个玩家一个普通 {@link SimpleContainer}，菜单槽位直接引用它；
 * 内容在登录时从旧存档灌入，之后每次变化同步写回旧存档的 45–48 槽。
 *
 * <p>监听器只持 {@link WeakReference}，玩家下线被回收后整条链可以一起被回收；
 * 拿不到 server（构造期 / 客户端）时跳过落盘。
 */
public final class Upgrades {

    private static final Map<Player, SimpleContainer> CONTAINERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private Upgrades() {}

    public static SimpleContainer of(Player player) {
        return CONTAINERS.computeIfAbsent(player, Upgrades::create);
    }

    /** 登录：把旧侧面板存档里的 45–48 灌进常驻容器。 */
    public static void load(Player player) {
        SimpleContainer legacy = PanelStorage.containerFor(player);
        SimpleContainer container = of(player);
        for (int i = 0; i < SidePanel.UPGRADE_COLS; i++) {
            container.setItem(i, legacy.getItem(SidePanel.UPGRADE_START + i).copy());
        }
    }

    public static void copy(Player from, Player to) {
        SimpleContainer source = of(from);
        SimpleContainer target = of(to);
        for (int i = 0; i < SidePanel.UPGRADE_COLS; i++) {
            target.setItem(i, source.getItem(i).copy());
        }
    }

    private static SimpleContainer create(Player player) {
        SimpleContainer container = new SimpleContainer(SidePanel.UPGRADE_COLS);
        WeakReference<Player> owner = new WeakReference<>(player);
        container.addListener(ignored -> {
            Player alive = owner.get();
            if (alive != null) {
                persist(alive, container);
            }
        });
        return container;
    }

    private static void persist(Player player, SimpleContainer container) {
        if (player.getServer() == null) {
            return;
        }
        SimpleContainer legacy = PanelStorage.containerFor(player);
        for (int i = 0; i < SidePanel.UPGRADE_COLS; i++) {
            legacy.setItem(SidePanel.UPGRADE_START + i, container.getItem(i).copy());
        }
    }
}
