package com.catoxide.catoxidesbattlerebuild;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CatoxidesBattleRebuildConstants {
    public static final String MODID = "catoxidesbattlerebuild";
    public static final String MOD_VERSION = "0.2.0-alpha";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    /** 当前运行环境是否客户端。pack 编译类路没有 FML 类，pack 代码经 core 查询此值（A7 配套）。
     *  FQN 核实：FMLEnvironment 在 net.neoforged.fml.loading（loader jar），Dist 在 net.neoforged.api.distmarker。 */
    public static final boolean CLIENT = net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT;

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static void init() {}
}
