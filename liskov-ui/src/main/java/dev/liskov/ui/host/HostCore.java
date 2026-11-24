package dev.liskov.ui.host;

import com.mojang.blaze3d.platform.Window;
import dev.liskov.core.LiskovFs;
import dev.liskov.ui.theme.MubTheme;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiMouseCursor;
import imgui.internal.ImGuiContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single shared Dear ImGui context, SDL3 input router, and RenderPearl frame loop across all Liskov-powered mods.
 */
public final class HostCore {
    private static final Logger LOG = LoggerFactory.getLogger("liskov-ui");

    private static final List<UiLayer> LAYERS = new CopyOnWriteArrayList<>();
    private static final Set<String> FAILED_LAYERS = new HashSet<>();

    private static boolean initialized;
    private static boolean disabled;
    private static float scale = 1.0f;
    private static ImGuiContext ctx;
    private static long lastFrameNanos;
    private static boolean textInputOn;
    private static RpRenderer renderer;

    private static boolean cursorCaptured;
    private static LiskovUiHost.Cursor cursorRequest;
    private static final long[] STD_CURSORS = new long[LiskovUiHost.Cursor.values().length];

    private static final boolean[] SDL_KEYS_DOWN = new boolean[512];
    private static final boolean[] BUTTONS_DOWN = new boolean[8];
    private static double mouseX, mouseY;
    private static double virtualX, virtualY;

    private HostCore() {}

    static void register(UiLayer layer) {
        if (layer == null) return;
        LAYERS.removeIf(l -> l.id().equals(layer.id()));
        LAYERS.add(layer);
        LAYERS.sort((a, b) -> Integer.compare(a.order(), b.order()));
    }

    private static List<UiLayer> visibleLayers() {
        List<UiLayer> out = null;
        for (UiLayer l : LAYERS) {
            boolean vis;
            try {
                vis = l.visible() && !FAILED_LAYERS.contains(l.id());
            } catch (Throwable t) {
                fail(l, t);
                continue;
            }
            if (vis) {
                if (out == null) out = new ArrayList<>(4);
                out.add(l);
            }
        }
        return out == null ? List.of() : out;
    }

    private static void fail(UiLayer l, Throwable t) {
        if (FAILED_LAYERS.add(l.id())) LOG.warn("[Liskov] UI layer '{}' disabled after an error", l.id(), t);
    }

    static float scale() { return scale; }

    static void setScale(float s) {
        scale = Math.max(0.5f, Math.min(4f, s));
        MubTheme.config().uiScale = scale;
        MubTheme.saveToDisk();
    }

    static boolean isInitialized() { return initialized; }

    static boolean imguiWantsKeyboard() {
        if (!initialized) return false;
        ImGui.setCurrentContext(ctx);
        return ImGui.getIO().getWantCaptureKeyboard();
    }

    static boolean imguiWantsMouse() {
        if (!initialized) return false;
        ImGui.setCurrentContext(ctx);
        return ImGui.getIO().getWantCaptureMouse();
    }

    static boolean imguiWantsTextInput() {
        if (!initialized) return false;
        ImGui.setCurrentContext(ctx);
        return ImGui.getIO().getWantTextInput();
    }

    static void setCursor(LiskovUiHost.Cursor c) { cursorRequest = c; }
    static boolean isCursorCaptured() { return cursorCaptured; }

    static void captureCursor(boolean on) {
        if (on == cursorCaptured) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return;
        cursorCaptured = on;
        if (on) {
            virtualX = mouseX;
            virtualY = mouseY;
        }
        SDLMouse.SDL_SetWindowRelativeMouseMode(mc.getWindow().handle(), on);
    }

    private static int sdlCursorId(LiskovUiHost.Cursor c) {
        return switch (c) {
            case TEXT -> SDLMouse.SDL_SYSTEM_CURSOR_TEXT;
            case CROSSHAIR -> SDLMouse.SDL_SYSTEM_CURSOR_CROSSHAIR;
            case HAND -> SDLMouse.SDL_SYSTEM_CURSOR_POINTER;
            case RESIZE_EW -> SDLMouse.SDL_SYSTEM_CURSOR_EW_RESIZE;
            case RESIZE_NS -> SDLMouse.SDL_SYSTEM_CURSOR_NS_RESIZE;
            case RESIZE_ALL -> SDLMouse.SDL_SYSTEM_CURSOR_MOVE;
            case NOT_ALLOWED -> SDLMouse.SDL_SYSTEM_CURSOR_NOT_ALLOWED;
            default -> SDLMouse.SDL_SYSTEM_CURSOR_DEFAULT;
        };
    }

    private static long standardCursor(LiskovUiHost.Cursor c) {
        int i = c.ordinal();
        if (STD_CURSORS[i] == 0L) {
            long h = SDLMouse.SDL_CreateSystemCursor(sdlCursorId(c));
            if (h == 0L) h = SDLMouse.SDL_CreateSystemCursor(SDLMouse.SDL_SYSTEM_CURSOR_DEFAULT);
            STD_CURSORS[i] = h;
        }
        return STD_CURSORS[i];
    }

    private static LiskovUiHost.Cursor fromImGui(int cursor) {
        return switch (cursor) {
            case ImGuiMouseCursor.TextInput -> LiskovUiHost.Cursor.TEXT;
            case ImGuiMouseCursor.ResizeAll -> LiskovUiHost.Cursor.RESIZE_ALL;
            case ImGuiMouseCursor.ResizeNS -> LiskovUiHost.Cursor.RESIZE_NS;
            case ImGuiMouseCursor.ResizeEW -> LiskovUiHost.Cursor.RESIZE_EW;
            case ImGuiMouseCursor.Hand -> LiskovUiHost.Cursor.HAND;
            case ImGuiMouseCursor.NotAllowed -> LiskovUiHost.Cursor.NOT_ALLOWED;
            default -> LiskovUiHost.Cursor.ARROW;
        };
    }

    static boolean isKeyDown(int glfwKey) {
        int sc = InputMap.sdlScancode(glfwKey);
        return sc >= 0 && SDL_KEYS_DOWN[sc];
    }

    static boolean isMouseDown(int glfwButton) {
        return glfwButton >= 0 && glfwButton < BUTTONS_DOWN.length && BUTTONS_DOWN[glfwButton];
    }

    static double cursorX() { return cursorCaptured ? virtualX : mouseX; }
    static double cursorY() { return cursorCaptured ? virtualY : mouseY; }

    public static void renderFrame() {
        if (disabled || LAYERS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Window win = mc.getWindow();
        if (win == null || win.isIconified() || win.getWidth() <= 0 || win.getHeight() <= 0) return;
        List<UiLayer> layers = visibleLayers();
        if (layers.isEmpty()) return;
        try {
            if (!initialized && !init()) return;
            render(mc, layers);
        } catch (Throwable t) {
            disabled = true;
            LOG.warn("[Liskov] ImGui host disabled after an error", t);
        }
    }

    private static boolean init() {
        try {
            Path nativesDir = LiskovFs.isInitialized()
                    ? LiskovFs.get().liskovRoot().resolve(".cache").resolve("natives")
                    : FabricLoader.getInstance().getConfigDir().resolve("liskov-natives");
            ImGuiNatives.load(nativesDir, HostCore.class.getClassLoader());
            MubTheme.loadFromDisk();
            if (MubTheme.config().uiScale >= 0.5f && MubTheme.config().uiScale <= 4f) {
                scale = MubTheme.config().uiScale;
            }

            ctx = ImGui.createContext();
            ImGui.setCurrentContext(ctx);
            ImGuiIO io = ImGui.getIO();
            io.setIniFilename(null);
            io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard | ImGuiConfigFlags.DockingEnable);
            io.setConfigDockingWithShift(true);

            io.setGetClipboardTextFn(new imgui.callback.ImStrSupplier() {
                @Override
                public String get() {
                    Minecraft mc = Minecraft.getInstance();
                    return mc != null && mc.keyboardHandler != null ? mc.keyboardHandler.getClipboard() : "";
                }
            });
            io.setSetClipboardTextFn(new imgui.callback.ImStrConsumer() {
                @Override
                public void accept(String text) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.keyboardHandler != null) {
                        mc.keyboardHandler.setClipboard(text == null ? "" : text);
                    }
                }
            });

            MubTheme.apply(ImGui.getStyle(), scale);
            if (!MubTheme.loadFonts(io, scale)) {
                io.getFonts().addFontDefault();
            }
            io.setBackendPlatformName("liskov-sdl3");

            renderer = new RpRenderer();
            renderer.init(io);
            initialized = true;
            return true;
        } catch (Throwable t) {
            disabled = true;
            LOG.warn("[Liskov] ImGui host disabled: {}", t.toString());
            return false;
        }
    }

    private static void refreshScaleAndStyle() {
        if (MubTheme.needsFonts(scale)) {
            ImGuiIO io = ImGui.getIO();
            io.getFonts().clear();
            MubTheme.loadFonts(io, scale);
            renderer.createFontTexture(io);
            MubTheme.apply(ImGui.getStyle(), scale);
        } else if (MubTheme.needsStyleApply()) {
            MubTheme.apply(ImGui.getStyle(), scale);
        }
    }

    private static void platformNewFrame(Minecraft mc, ImGuiIO io) {
        Window win = mc.getWindow();
        int sw = Math.max(1, win.getScreenWidth()), sh = Math.max(1, win.getScreenHeight());
        io.setDisplaySize(sw, sh);
        io.setDisplayFramebufferScale((float) win.getWidth() / sw, (float) win.getHeight() / sh);
        long now = System.nanoTime();
        io.setDeltaTime(lastFrameNanos == 0 ? 1f / 60f : Math.max(1e-4f, (now - lastFrameNanos) / 1e9f));
        lastFrameNanos = now;

        boolean wantText = io.getWantTextInput();
        if (wantText != textInputOn) {
            textInputOn = wantText;
            if (wantText) SDLKeyboard.SDL_StartTextInput(win.handle());
            else SDLKeyboard.SDL_StopTextInput(win.handle());
        }
        if ((io.getConfigFlags() & ImGuiConfigFlags.NoMouseCursorChange) == 0 && io.getWantCaptureMouse()) {
            SDLMouse.SDL_SetCursor(standardCursor(fromImGui(ImGui.getMouseCursor())));
        }
    }

    private static void render(Minecraft mc, List<UiLayer> layers) {
        ImGui.setCurrentContext(ctx);
        refreshScaleAndStyle();
        ImGuiIO io = ImGui.getIO();
        LiskovUiHost.Cursor wanted = cursorRequest;
        cursorRequest = null;
        if (mc.mouseHandler.isMouseGrabbed() || cursorCaptured) {
            io.addConfigFlags(ImGuiConfigFlags.NoMouse | ImGuiConfigFlags.NoMouseCursorChange);
            io.addMousePosEvent(-Float.MAX_VALUE, -Float.MAX_VALUE);
        } else {
            io.removeConfigFlags(ImGuiConfigFlags.NoMouse);
            if (wanted != null && wanted != LiskovUiHost.Cursor.AUTO) {
                io.addConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
                SDLMouse.SDL_SetCursor(standardCursor(wanted));
            } else {
                io.removeConfigFlags(ImGuiConfigFlags.NoMouseCursorChange);
            }
        }
        io.setWantSetMousePos(false);
        platformNewFrame(mc, io);
        ImGui.newFrame();

        float[] dockedVp = null;
        for (UiLayer l : layers) {
            if (FAILED_LAYERS.contains(l.id())) continue;
            try {
                float[] vp = l.dockedGameViewport();
                if (vp != null) dockedVp = vp;
                l.draw();
            } catch (Throwable t) {
                fail(l, t);
            }
        }

        ImGui.render();
        renderer.render(mc, ImGui.getDrawData(), dockedVp);
    }

    private static boolean routing(long window, Minecraft mc) {
        return initialized && !disabled && mc != null && mc.getWindow() != null && window == mc.getWindow().handle()
                && !visibleLayers().isEmpty();
    }

    private static boolean anyWantsKeyboard() {
        for (UiLayer l : visibleLayers()) {
            try {
                if (l.wantsKeyboard()) return true;
            } catch (Throwable t) {
                fail(l, t);
            }
        }
        return false;
    }

    private static boolean anyWantsKey(int key, int scancode, int action, int mods) {
        for (UiLayer l : visibleLayers()) {
            try {
                if (l.wantsKey(key, scancode, action, mods)) return true;
            } catch (Throwable t) {
                fail(l, t);
            }
        }
        return false;
    }

    public static boolean onKey(long window, int sdlScancode, int mcAction, int sdlMods) {
        if (sdlScancode >= 0 && sdlScancode < SDL_KEYS_DOWN.length) SDL_KEYS_DOWN[sdlScancode] = mcAction != 0;
        Minecraft mc = Minecraft.getInstance();
        if (!routing(window, mc)) return false;
        int key = InputMap.glfwKey(sdlScancode), action = InputMap.glfwAction(mcAction), mods = InputMap.glfwMods(sdlMods);
        boolean wants = anyWantsKey(key, sdlScancode, action, mods);
        ImGui.setCurrentContext(ctx);
        if (wants || action == InputMap.GLFW_RELEASE) {
            int ik = InputMap.imguiKey(key);
            if (ik != 0 && action != InputMap.GLFW_REPEAT) ImGui.getIO().addKeyEvent(ik, action == InputMap.GLFW_PRESS);
            for (UiLayer l : visibleLayers()) {
                try {
                    l.onKey(key, sdlScancode, action, mods);
                } catch (Throwable t) {
                    fail(l, t);
                }
            }
        }
        return wants;
    }

    public static boolean onChar(long window, int codepoint) {
        Minecraft mc = Minecraft.getInstance();
        if (!routing(window, mc)) return false;
        if (!anyWantsKeyboard()) return false;
        ImGui.setCurrentContext(ctx);
        ImGui.getIO().addInputCharacter(codepoint);
        return true;
    }

    public static boolean onMouseMove(long window, double x, double y, double dx, double dy) {
        mouseX = x;
        mouseY = y;
        if (cursorCaptured) {
            virtualX += dx;
            virtualY += dy;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!routing(window, mc) || mc.mouseHandler.isMouseGrabbed()) return false;
        ImGui.setCurrentContext(ctx);
        if (!cursorCaptured) ImGui.getIO().addMousePosEvent((float) x, (float) y);
        boolean wants = false;
        double lx = cursorX(), ly = cursorY();
        for (UiLayer l : visibleLayers()) {
            try {
                if (l.wantsMouse() || cursorCaptured) {
                    wants = true;
                    l.onMouseMove(lx, ly);
                }
            } catch (Throwable t) {
                fail(l, t);
            }
        }
        return wants;
    }

    public static boolean onMouseButton(long window, int sdlButton, int mcAction, int sdlMods) {
        int button = InputMap.glfwButton(sdlButton), action = InputMap.glfwAction(mcAction), mods = InputMap.glfwMods(sdlMods);
        if (button >= 0 && button < BUTTONS_DOWN.length) BUTTONS_DOWN[button] = action != InputMap.GLFW_RELEASE;
        Minecraft mc = Minecraft.getInstance();
        if (!routing(window, mc) || mc.mouseHandler.isMouseGrabbed()) return false;
        boolean wants = false;
        for (UiLayer l : visibleLayers()) {
            try {
                if (l.wantsMouse()) wants = true;
            } catch (Throwable t) {
                fail(l, t);
            }
        }
        ImGui.setCurrentContext(ctx);
        if (wants || action == InputMap.GLFW_RELEASE) {
            ImGui.getIO().addMouseButtonEvent(button, action == InputMap.GLFW_PRESS);
            for (UiLayer l : visibleLayers()) {
                try {
                    if (action == InputMap.GLFW_RELEASE || l.wantsMouse()) l.onMouseButton(button, action, mods);
                } catch (Throwable t) {
                    fail(l, t);
                }
            }
        }
        return wants;
    }

    public static boolean onMouseScroll(long window, double dx, double dy) {
        Minecraft mc = Minecraft.getInstance();
        if (!routing(window, mc) || mc.mouseHandler.isMouseGrabbed()) return false;
        boolean wants = false;
        for (UiLayer l : visibleLayers()) {
            try {
                if (l.wantsMouse()) {
                    wants = true;
                    l.onScroll(dx, dy);
                }
            } catch (Throwable t) {
                fail(l, t);
            }
        }
        if (wants) {
            ImGui.setCurrentContext(ctx);
            ImGui.getIO().addMouseWheelEvent((float) dx, (float) dy);
        }
        return wants;
    }
}
