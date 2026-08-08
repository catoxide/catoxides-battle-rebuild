package com.catoxide.catoxidesbattlerebuild.core.structure.blueprint;

import java.util.ArrayList;
import java.util.List;

/**
 * Template pool - groups structure pieces for jigsaw resolution.
 */
public class TemplatePool {
    public String name;
    public List<PoolElement> elements = new ArrayList<>();
    public String fallbackPool = "minecraft:empty";

    public TemplatePool() {}
    public TemplatePool(String name) { this.name = name; }

    public void addElement(PoolElement element) {
        this.elements.add(element);
    }

    public static class PoolElement {
        public String template;
        public String projection = "ground";
        public int weight = 1;
        public List<String> processors = new ArrayList<>();

        public PoolElement() {}
        public PoolElement(String template) { this.template = template; }
    }
}
