package com.example.blueprintbuilder.canvas.cache;

/**
 * Shared render settings for the Canvas Cache renderer. Set by the {@code /canvasCache lod} command and read
 * by the client renderer (same JVM in singleplayer). Pure data, no Minecraft types.
 */
public final class CanvasRenderSettings {

    /** -1 = automatic distance-based LOD; otherwise a forced LOD level that overrides distance. */
    private static volatile int lodOverride = -1;

    /** Debug: when true the renderer draws every project at full opacity ignoring distance + frustum. */
    private static volatile boolean debugAlwaysRender = false;

    private CanvasRenderSettings() {}

    public static void setDebugAlwaysRender(boolean value) {
        debugAlwaysRender = value;
    }

    public static boolean isDebugAlwaysRender() {
        return debugAlwaysRender;
    }

    public static void setAuto() {
        lodOverride = -1;
    }

    public static void setForcedLod(int level) {
        lodOverride = Math.max(0, level);
    }

    public static boolean isAuto() {
        return lodOverride < 0;
    }

    /** The forced LOD level, or -1 when automatic. */
    public static int forcedLod() {
        return lodOverride;
    }
}
