package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * 运行时合成「玩家本人皮肤 + 剑」的 96×64 贴图（叠加层身体层 = 玩家自己的皮肤，不是 Steve）。
 *
 * <p>布局与静态复合贴图一致：
 * <ul>
 *   <li>左 64×64 @(0,0) = 玩家皮肤（{@code player.getSkin().texture()} 资源，须为 64×64）</li>
 *   <li>右 32×64 @(64,0) = 剑区（含 90° 转置下区），从静态复合贴图
 *       {@code <modid>:textures/entity/player_wrist.png} 裁取（剑 UV 不变）</li>
 * </ul>
 *
 * <p>机制：{@link DynamicTexture}（构造即在渲染线程上传）→
 * {@code TextureManager.register(prefix, tex)} 返回唯一 ResourceLocation →
 * 交给 Spark 的 {@code setTextureLocation}。渲染时
 * {@code RenderType.entityTranslucent(location)} 经 TextureManager 命中该贴图。
 *
 * <p>降级：皮肤资源缺失 / 非 64×64（legacy 64×32）/ 读取异常 → 返回 null，
 * 调用方回退静态复合贴图（Steve 身体）。
 *
 * <p>调用方负责 {@code TextureManager.release(loc)}（停用/换肤/卸世界时）。
 */
public final class WristSkinComposer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation STATIC_COMP = ResourceLocation.fromNamespaceAndPath(
            CatoxidesBattleRebuildConstants.MODID, "textures/entity/player_wrist.png");
    private static final String REG_PREFIX = "player_wrist_skin";
    /** 回退原因节流（重试路径每 20 tick 调一次，避免刷日志）：key→上次打印时间 */
    private static final java.util.Map<String, Long> LAST_FALLBACK_LOG = new java.util.HashMap<>();

    private WristSkinComposer() {}

    /** 同 key 5 秒内只打一次 WARN（重试路径高频调用时防刷屏） */
    private static void warnFallback(String key, String fmt, Object... args) {
        long now = System.currentTimeMillis();
        Long last = LAST_FALLBACK_LOG.get(key);
        if (last != null && now - last < 5000) {
            return;
        }
        LAST_FALLBACK_LOG.put(key, now);
        LOGGER.warn("[SwordPlayerModel] 皮肤合成回退静态贴图 [" + key + "]: " + fmt, args);
    }

    /**
     * 合成并注册贴图。
     *
     * @return 注册的 ResourceLocation；失败返回 null（调用方用静态贴图）
     */
    public static ResourceLocation composeAndRegister(Minecraft mc, AbstractClientPlayer player) {
        NativeImage skin = null;
        NativeImage stat = null;
        NativeImage comp = null;
        try {
            ResourceLocation skinLoc = player.getSkin().texture();
            Optional<Resource> res = mc.getResourceManager().getResource(skinLoc);
            if (res.isEmpty()) {
                warnFallback("资源缺失:" + skinLoc, "皮肤资源 {} 未在 ResourceManager 中（未下载/未加载）", skinLoc);
                return null;
            }
            skin = NativeImage.read(res.get().open());
            if (skin.getWidth() != 64 || skin.getHeight() != 64) {
                warnFallback("非64x64:" + skinLoc, "皮肤 {} 为 {}x{}（非 64x64，legacy 无外层）",
                        skinLoc, skin.getWidth(), skin.getHeight());
                return null;
            }
            Optional<Resource> statRes = mc.getResourceManager().getResource(STATIC_COMP);
            if (statRes.isEmpty()) {
                warnFallback("静态贴图缺失", "静态复合贴图 {} 缺失", STATIC_COMP);
                return null;
            }
            stat = NativeImage.read(statRes.get().open());
            comp = new NativeImage(96, 64, true);
            // ⚠️ 1.21.1 copyRect 语义（字节码实证）：this.copyRect(dst, sx, sy, dx, dy, dw, dh, flip, scale)
            // —— 接收者=源图，第一参=目标图，(sx,sy)=源偏移，(dx,dy)=目标偏移，(dw,dh)=目标尺寸。
            // 此前写反方向（把空 comp 拷进 skin/stat）→ 复合贴图全透明 → 模型隐形（2026-10-01 实机定位）。
            skin.copyRect(comp, 0, 0, 0, 0, 64, 64, false, false);      // 身体 = 玩家皮肤 → comp@(0,0)
            // 左腿 UV 对齐 vanilla 1.21.1：反编译 HumanoidModel.createMesh 中
            // left_leg = texOffs(0,16).mirror()（左腿采样右腿区 (0,16)-(16,32) 的镜像）；
            // 我们的模型左腿采样经典布局 (16,48) 区 ⇒ 把右腿区水平镜像拷到 (16,48)-(32,64)，
            // 使左腿纹理采样与原版渲染逐像素一致（该合图区域无其他用途，安全覆盖）。
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    comp.setPixelRGBA(16 + x, 48 + y, skin.getPixelRGBA(15 - x, 16 + y));
                }
            }
            stat.copyRect(comp, 64, 0, 64, 0, 32, 64, false, false);    // 剑区 = 静态贴图右半 → comp@(64,0)
            // DynamicTexture 构造即上传（非渲染线程会自动调度到渲染线程）；
            // comp 由纹理持有，release(loc) 时随纹理关闭，这里不再 close
            DynamicTexture tex = new DynamicTexture(comp);
            comp = null;
            ResourceLocation loc = mc.getTextureManager().register(REG_PREFIX, tex);
            LOGGER.info("[SwordPlayerModel] 皮肤合成 OK: {} ({}x{}) -> {}", skinLoc, skin.getWidth(), skin.getHeight(), loc);
            return loc;
        } catch (Exception e) {
            LOGGER.warn("[SwordPlayerModel] 皮肤合成失败 → 回退静态贴图: {}", e.getMessage());
            return null;
        } finally {
            if (skin != null) skin.close();
            if (stat != null) stat.close();
            if (comp != null) comp.close();
        }
    }
}
