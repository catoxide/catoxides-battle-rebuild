// TransformModule.java - 变换模块基类
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 变换模块基类 - 所有变换模块的父类
 */
public abstract class TransformModule {
    protected boolean enabled = true;
    protected String moduleName;

    public TransformModule(String name) {
        this.moduleName = name;
    }

    public abstract Vec3 process(TransformContext context);

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getModuleName() {
        return moduleName;
    }
}
