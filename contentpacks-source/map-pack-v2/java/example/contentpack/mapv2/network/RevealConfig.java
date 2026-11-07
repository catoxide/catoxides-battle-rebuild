package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;

/**
 * 探明范围的服务端配置（map-pack 的 server 分区）。
 *
 * <p>半径是「每个玩家周围多少区块算探明」；0 表示关闭 —— 服务端不回快照，
 * 客户端保持旧行为（预测铺满视口）。
 */
public final class RevealConfig {

    public static final int DEFAULT_RADIUS_CHUNKS = 64;

    private static volatile PackConfig config;

    private RevealConfig() {
    }

    public static void bind(PackConfig packConfig) {
        config = packConfig;
    }

    public static int radiusChunks() {
        PackConfig local = config;
        int radius = local == null ? DEFAULT_RADIUS_CHUNKS : local.getInt("revealRadiusChunks");
        return Math.max(0, Math.min(256, radius));
    }
}