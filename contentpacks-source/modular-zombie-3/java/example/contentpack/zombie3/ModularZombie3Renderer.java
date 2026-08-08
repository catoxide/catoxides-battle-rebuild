package example.contentpack.zombie3;

import cn.solarmoon.spark_core.animation.renderer.GeoLivingEntityRenderer;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

/**
 * ModularZombie3 的客户端渲染器
 * <p>使用 Spark-Core 的 GeoLivingEntityRenderer，自动从 ModelIndex 加载模型。
 */
public class ModularZombie3Renderer extends GeoLivingEntityRenderer<ModularZombie3> {
    public ModularZombie3Renderer(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context) {
        super(context, 0.5f);
        LogManager.clientStartup("ModularZombie3Renderer", "Initialized (ContentPack, SparkCore GeoLivingEntityRenderer)");
    }
}
