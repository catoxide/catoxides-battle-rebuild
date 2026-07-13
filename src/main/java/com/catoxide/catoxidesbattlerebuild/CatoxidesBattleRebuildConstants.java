package com.catoxide.catoxidesbattlerebuild;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CatoxidesBattleRebuildConstants {
    public static final String MODID = "catoxidesbattlerebuild";
    public static final String MOD_VERSION = "0.2.0-alpha";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static void init() {}
}
