package com.catoxide.catoxidesbattlerebuild.client.tick;

import com.catoxide.catoxidesbattlerebuild.client.geometry.ClientBoneCollection;
import com.catoxide.catoxidesbattlerebuild.client.geometry.DoubleBufferedBoneData;
import com.catoxide.catoxidesbattlerebuild.client.geometry.EntityBoneManager;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

public class ClientBoneTickManager {
    private static final ClientBoneTickManager INSTANCE = new ClientBoneTickManager();

    private ClientBoneTickManager() {}

    public static ClientBoneTickManager getInstance() {
        return INSTANCE;
    }

    public void tickEntity(Entity entity, GeoModel<?> geoModel) {
        DoubleBufferedBoneData bufferedData = EntityBoneManager.getInstance().getDoubleBufferedData(entity.getId());
        if (bufferedData == null) return;

        bufferedData.swapBuffers();

        LogManager.clientDebug("ClientBoneTickManager", "Ticked entity: entityId={}", entity.getId());
    }
}
