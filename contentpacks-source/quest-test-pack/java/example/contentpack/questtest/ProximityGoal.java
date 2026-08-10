package example.contentpack.questtest;

import com.catoxide.catoxidesbattlerebuild.core.quest.IQuestGoal;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * 示例目标：玩家到达指定坐标附近（距离 < 半径）即完成。
 * <p>验证基座 IQuestGoal 接口链路（isComplete/getProgress）。
 */
public class ProximityGoal implements IQuestGoal {

    private final Vec3 target;
    private final float radius;

    public ProximityGoal(Vec3 target, float radius) {
        this.target = target;
        this.radius = radius;
    }

    @Override
    public String getType() {
        return "proximity";
    }

    @Override
    public boolean isComplete(QuestInstance quest, ServerPlayer player) {
        return player.position().distanceToSqr(target) <= (double) radius * radius;
    }

    @Override
    public Map<String, Float> getProgress(QuestInstance quest, ServerPlayer player) {
        Map<String, Float> progress = new HashMap<>();
        progress.put("distance", (float) player.position().distanceTo(target));
        progress.put("radius", radius);
        return progress;
    }

    public Vec3 getTarget() {
        return target;
    }
}
