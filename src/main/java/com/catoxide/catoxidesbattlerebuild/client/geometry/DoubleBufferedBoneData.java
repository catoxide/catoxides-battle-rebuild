package com.catoxide.catoxidesbattlerebuild.client.geometry;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class DoubleBufferedBoneData {
    private volatile Map<String, ClientBoneCollection> frontBuffer;
    private volatile Map<String, ClientBoneCollection> backBuffer;
    private final Object swapLock = new Object();

    public DoubleBufferedBoneData() {
        this.frontBuffer = new HashMap<>();
        this.backBuffer = new HashMap<>();
        LogManager.clientDebug("DoubleBufferedBoneData", "DoubleBufferedBoneData initialized");
    }

    public void updateBackBuffer(Consumer<Map<String, ClientBoneCollection>> updater) {
        LogManager.clientDebug("DoubleBufferedBoneData", "Updating back buffer");
        int oldSize = backBuffer.size();
        updater.accept(backBuffer);
        int newSize = backBuffer.size();
        LogManager.clientDebug("DoubleBufferedBoneData", "Back buffer updated: oldSize={}, newSize={}",
                oldSize, newSize);
    }

    public void swapBuffers() {
        synchronized (swapLock) {
            Map<String, ClientBoneCollection> temp = frontBuffer;
            frontBuffer = backBuffer;
            backBuffer = temp;
            String message = String.format("Buffers swapped: frontBuffer size=%d", frontBuffer.size());
            LogManager.clientDebugThrottled("DoubleBufferedBoneData", message, 1000);
        }
    }

    public ClientBoneCollection getSafeBone(String boneName) {
        ClientBoneCollection bone = frontBuffer.get(boneName);
        String message = String.format("Getting safe bone: boneName=%s, exists=%b", boneName, bone != null);
        LogManager.clientDebugThrottled("DoubleBufferedBoneData", message, 500);
        return bone;
    }

    public Map<String, ClientBoneCollection> getSafeData() {
        Map<String, ClientBoneCollection> data = new HashMap<>(frontBuffer);
        String message = String.format("Getting safe data: size=%d", data.size());
        LogManager.clientDebugThrottled("DoubleBufferedBoneData", message, 500);
        return data;
    }
}
