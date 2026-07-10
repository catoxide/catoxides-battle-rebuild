package com.catoxide.catoxidesbattlerebuild.client.renderer;

import cn.solarmoon.spark_core.animation.renderer.GeoItemRenderer;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

/**
 * 铁剑物品渲染器
 * 继承 GeoItemRenderer，使用 Spark-Core 的默认渲染逻辑
 * 模型、动画、纹理通过 ICustomModelItem 接口和 ItemAnimatable 加载
 */
public class IronSwordRenderer extends GeoItemRenderer {

    public IronSwordRenderer() {
        LogManager.clientInfo("IronSwordRenderer", "IronSwordRenderer initialized");
    }
}
