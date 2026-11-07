package example.contentpack.sablev2.map.client;

import com.catoxide.catoxidesbattlerebuild.core.sable.PoseData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 客户端 sub-level 句柄查询（本包唯一把 UUID 换成 sable 对象的入口）。
 *
 * <p>服务层只暴露防腐值类型 {@code SubLevelInfo}，但 GPU 图解渲染需要 sable 的
 * {@link ClientSubLevel} 本体，叠加层也需要插值后的 {@code renderPose}。两者都只
 * 在客户端地图路径里用。
 */
final class SableClientSubLevels {

    private SableClientSubLevels() {
    }

    @Nullable
    static ClientSubLevel get(ClientLevel level, UUID id) {
        if (level == null || id == null) {
            return null;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        SubLevel sub = container.getSubLevel(id);
        return sub instanceof ClientSubLevel client ? client : null;
    }

    /**
     * 插值后的渲染姿态。叠加层的 2D 投影与离屏渲染共用它，
     * 避免「船体图像按 renderPose、贴图位置按 logicalPose」的错位。
     */
    @Nullable
    static PoseData renderPose(ClientSubLevel sub, float partialTick) {
        if (sub == null) {
            return null;
        }
        Pose3dc pose = sub.renderPose(partialTick);
        return new PoseData(
                pose.position().x(), pose.position().y(), pose.position().z(),
                pose.orientation().x(), pose.orientation().y(),
                pose.orientation().z(), pose.orientation().w(),
                pose.scale().x(), pose.scale().y(), pose.scale().z(),
                pose.rotationPoint().x(), pose.rotationPoint().y(), pose.rotationPoint().z());
    }
}
