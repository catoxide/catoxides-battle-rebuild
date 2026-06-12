package com.catoxide.catoxidesbattlerebuild.client.event;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import cn.solarmoon.spark_core.animation.anim.KeyAnimData;
import cn.solarmoon.spark_core.animation.model.BonePose;
import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.event.BoneUpdateEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber
public class SparkBoneUpdateListener {

    @SubscribeEvent
    public static void onBoneUpdate(BoneUpdateEvent event) {
        ModelInstance model = event.getModel();
        BonePose bonePose = event.getBonePose();
        
        // 可以在这里添加自定义的骨骼变换逻辑
        // 例如：修改骨骼位置、旋转、缩放
        
        KeyAnimData currentTransform = event.getNewTransform();
        KeyAnimData previousTransform = event.getOldTransform();
        
        LogManager.clientDebug("SparkBoneUpdateListener", 
            "Bone updated: model={}, bone={}, pos={}, rot={}, scale={}",
            model.getIndex(),
            bonePose.getName(),
            currentTransform.getPosition(),
            currentTransform.getRotation(),
            currentTransform.getScale()
        );
        
        // 如果需要修改骨骼变换，可以在这里修改 event.newTransform
        // event.setNewTransform(new KeyAnimData(...));
    }
}
