package dev.liskov.ui.host;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.liskov.ui.theme.MubTheme;

import java.util.function.LongFunction;

/**
 * Public entry point of Liskov's shared Dear ImGui host (superseding {@code cg-uihost}).
 */
public final class LiskovUiHost {
    public enum Cursor { AUTO, ARROW, TEXT, CROSSHAIR, HAND, RESIZE_EW, RESIZE_NS, RESIZE_ALL, NOT_ALLOWED }

    private LiskovUiHost() {}

    public static void register(UiLayer layer) { HostCore.register(layer); }
    public static MubTheme.Preset themePreset() { return MubTheme.preset(); }
    public static void setThemePreset(MubTheme.Preset preset) { MubTheme.setPreset(preset); }
    public static float scale() { return HostCore.scale(); }
    public static void setScale(float scale) { HostCore.setScale(scale); }
    public static boolean isInitialized() { return HostCore.isInitialized(); }
    public static boolean imguiWantsKeyboard() { return HostCore.imguiWantsKeyboard(); }
    public static boolean imguiWantsMouse() { return HostCore.imguiWantsMouse(); }
    public static boolean imguiWantsTextInput() { return HostCore.imguiWantsTextInput(); }
    public static void setCursor(Cursor cursor) { HostCore.setCursor(cursor); }
    public static void captureCursor(boolean capture) { HostCore.captureCursor(capture); }
    public static boolean isCursorCaptured() { return HostCore.isCursorCaptured(); }
    public static boolean isKeyDown(int glfwKey) { return HostCore.isKeyDown(glfwKey); }
    public static boolean isMouseDown(int glfwButton) { return HostCore.isMouseDown(glfwButton); }
    public static double cursorX() { return HostCore.cursorX(); }
    public static double cursorY() { return HostCore.cursorY(); }
    public static boolean blocksMouseGrab() { return HostCore.blocksMouseGrab(); }
    public static void regrabMouseIfFree() { HostCore.regrabMouseIfFree(); }
    public static boolean isDockedViewportActive() { return HostCore.isDockedViewportActive(); }
    public static double remapMouseX(double x) { return HostCore.remapMouseX(x); }
    public static double remapMouseY(double y) { return HostCore.remapMouseY(y); }
    public static boolean isBackendSupported() { return HostCore.isBackendSupported(); }
    public static void notifyResourcesReload() { HostCore.notifyResourcesReload(); }

    public static long registerTexture(GpuTextureView view) {
        return RpRenderer.registerTexture(view);
    }

    public static void unregisterTexture(long texId) {
        RpRenderer.unregisterTexture(texId);
    }

    public static void setTextureLookup(LongFunction<GpuTextureView> lookup) {
        RpRenderer.extraTextureLookup = lookup == null ? id -> null : lookup;
    }
}
