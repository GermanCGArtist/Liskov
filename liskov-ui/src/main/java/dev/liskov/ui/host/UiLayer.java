package dev.liskov.ui.host;

/**
 * One piece of ImGui UI. Register once with {@link LiskovUiHost#register(UiLayer)}.
 */
public interface UiLayer {
    String id();

    int order();

    boolean visible();

    void draw();

    default boolean wantsKeyboard() {
        return LiskovUiHost.imguiWantsKeyboard();
    }

    default boolean wantsMouse() {
        return LiskovUiHost.imguiWantsMouse();
    }

    default boolean wantsKey(int key, int scancode, int action, int mods) {
        return wantsKeyboard();
    }

    default void onKey(int key, int scancode, int action, int mods) {}

    default void onMouseButton(int button, int action, int mods) {}

    default void onMouseMove(double x, double y) {}

    default void onScroll(double dx, double dy) {}

    /**
     * Optional docked game-viewport rectangle {@code [x, y, w, h]} in display pixels.
     * When non-null, {@link RpRenderer} snapshots {@code mainRenderTarget}, clears to theme BG,
     * and draws the game frame inside that rectangle behind ImGui.
     */
    default float[] dockedGameViewport() {
        return null;
    }
}
