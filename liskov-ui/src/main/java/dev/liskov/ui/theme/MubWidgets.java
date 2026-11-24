package dev.liskov.ui.theme;

import dev.liskov.core.LiskovFileType;
import dev.liskov.core.SemanticColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Shared ModsUndBots terminal UI primitives (0px sharp geometry, 1px borders, 2-letter file monograms,
 * pixel segment bars, dot-grid backdrop, ToastHost, and Ctrl+P CommandPalette).
 */
public final class MubWidgets {
    private MubWidgets() {}

    // ------------------------------------------------------------------ Dot-Grid Backdrop

    public static void paintDotGridBackdrop(ImDrawList dl, float x0, float y0, float w, float h, float scale) {
        dl.addRectFilled(x0, y0, x0 + w, y0 + h, MubTheme.u32(MubTheme.BG));
        float step = Math.max(8f, 16f * scale);
        int dotCol = MubTheme.u32(MubTheme.DOT, 0.08f);
        for (float y = y0; y < y0 + h; y += step) {
            for (float x = x0; x < x0 + w; x += step) {
                dl.addRectFilled(x, y, x + 1.5f, y + 1.5f, dotCol);
            }
        }
    }

    // ------------------------------------------------------------------ Sharp Primitives

    public static void sectionHeader(String title) {
        MubTheme.push(MubTheme.small());
        MubTheme.textColored(MubTheme.GUTTER, "── " + title.toUpperCase(Locale.ROOT));
        ImGui.popFont();
    }

    public static boolean termButton(String label, SemanticColor tone, float width, float height) {
        int col = MubTheme.colorFor(tone);
        boolean subtle = tone == SemanticColor.INK_MUTED;
        boolean cta = tone == SemanticColor.CTA;
        int bg = cta ? MubTheme.CTA_BG : (subtle ? 0x00000000 : MubTheme.u32(col, 0.14f));
        int hoverBg = cta ? MubTheme.CTA_BG : (subtle ? MubTheme.u32(MubTheme.SURFACE) : MubTheme.u32(col, 0.92f));
        int fg = cta ? MubTheme.CTA_FG : (subtle ? MubTheme.DIM : col);

        ImGui.pushStyleColor(ImGuiCol.Button, bg == 0 ? 0 : (cta ? MubTheme.u32(bg) : bg));
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, cta ? MubTheme.u32(hoverBg, 0.88f) : hoverBg);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, cta ? MubTheme.u32(hoverBg, 0.78f) : hoverBg);
        ImGui.pushStyleColor(ImGuiCol.Border, MubTheme.u32(subtle ? MubTheme.LINE : col));
        ImGui.pushStyleColor(ImGuiCol.Text, MubTheme.u32(fg));
        boolean clicked = ImGui.button(label, width, height);
        ImGui.popStyleColor(5);
        return clicked;
    }

    public static boolean termButton(String label, SemanticColor tone) {
        return termButton(label, tone, 0f, 0f);
    }

    public static boolean termToggle(String id, String label, boolean current, SemanticColor tone) {
        String text = (current ? "[×] " : "[ ] ") + label + "##" + id;
        return termButton(text, current ? tone : SemanticColor.INK_MUTED);
    }

    public static void termBadge(String text, SemanticColor tone) {
        int col = MubTheme.colorFor(tone);
        MubTheme.push(MubTheme.monoS());
        ImVec2 sz = ImGui.calcTextSize(text);
        float px = ImGui.getCursorScreenPosX();
        float py = ImGui.getCursorScreenPosY();
        float w = sz.x + 10f;
        float h = Math.max(17f, sz.y + 4f);
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(px, py, px + w, py + h, MubTheme.u32(col, 0.14f));
        dl.addRect(px, py, px + w, py + h, MubTheme.u32(col));
        dl.addText(px + 5f, py + 2f, MubTheme.u32(col), text);
        ImGui.dummy(w, h);
        ImGui.popFont();
    }

    /** Adobe-style crisp 2-letter square file-type monogram badge ({@code Lm}, {@code Vp}, {@code La}, {@code Mp}, {@code Ms}, {@code Mc}, {@code Hm}). */
    public static void fileTypeBadge(LiskovFileType ft, boolean large) {
        int col = MubTheme.colorFor(ft.colorRole());
        float w = large ? 28f : 22f;
        float h = large ? 26f : 19f;
        MubTheme.push(large ? MubTheme.uiBold() : MubTheme.monoS());
        float px = ImGui.getCursorScreenPosX();
        float py = ImGui.getCursorScreenPosY();
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(px, py, px + w, py + h, MubTheme.u32(col, 0.16f));
        dl.addRect(px, py, px + w, py + h, MubTheme.u32(col), 0f, 0, large ? 2f : 1.5f);
        ImVec2 ts = ImGui.calcTextSize(ft.monogram());
        dl.addText(px + (w - ts.x) * 0.5f, py + (h - ts.y) * 0.5f, MubTheme.u32(col), ft.monogram());
        ImGui.dummy(w, h);
        ImGui.popFont();
    }

    public static void pixelSegments(float fraction, int segments, SemanticColor fillRole, float width, float height) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        int filled = Math.round(clamped * segments);
        float px = ImGui.getCursorScreenPosX();
        float py = ImGui.getCursorScreenPosY();
        float gap = 2f;
        float cellW = Math.max(2f, (width - gap * (segments - 1)) / segments);
        ImDrawList dl = ImGui.getWindowDrawList();
        int fillCol = MubTheme.u32(MubTheme.colorFor(fillRole));
        int emptyCol = MubTheme.u32(MubTheme.LINE);
        for (int i = 0; i < segments; i++) {
            float cx = px + i * (cellW + gap);
            dl.addRectFilled(cx, py, cx + cellW, py + height, i < filled ? fillCol : emptyCol);
        }
        ImGui.dummy(width, height);
    }

    // ------------------------------------------------------------------ ToastHost

    public static final class ToastHost {
        private record Toast(String msg, SemanticColor role, long expiresMs) {}
        private static final Deque<Toast> TOASTS = new ArrayDeque<>();

        private ToastHost() {}

        public static void push(String message, SemanticColor role) {
            if (message == null || message.isBlank()) return;
            synchronized (TOASTS) {
                TOASTS.addLast(new Toast(message, role == null ? SemanticColor.FOCUS : role, System.currentTimeMillis() + 3500L));
                while (TOASTS.size() > 5) TOASTS.removeFirst();
            }
        }

        public static void drawOverlay() {
            long now = System.currentTimeMillis();
            List<Toast> active;
            synchronized (TOASTS) {
                TOASTS.removeIf(t -> t.expiresMs <= now);
                if (TOASTS.isEmpty()) return;
                active = List.copyOf(TOASTS);
            }
            var vp = ImGui.getMainViewport();
            float x = vp.getPosX() + vp.getSizeX() - 16f;
            float y = vp.getPosY() + vp.getSizeY() - 16f;
            ImDrawList dl = ImGui.getForegroundDrawList();
            MubTheme.push(MubTheme.small());
            for (int i = active.size() - 1; i >= 0; i--) {
                Toast t = active.get(i);
                ImVec2 sz = ImGui.calcTextSize(t.msg);
                float bw = Math.min(460f, sz.x + 26f);
                float bh = 26f;
                float bx = x - bw;
                float by = y - bh;
                int accent = MubTheme.u32(MubTheme.colorFor(t.role));
                dl.addRectFilled(bx, by, bx + bw, by + bh, MubTheme.u32(MubTheme.PANEL, 0.96f));
                dl.addRect(bx, by, bx + bw, by + bh, MubTheme.u32(MubTheme.BORDER_HI));
                dl.addRectFilled(bx, by, bx + 3f, by + bh, accent);
                dl.addText(bx + 10f, by + (bh - sz.y) * 0.5f, MubTheme.u32(MubTheme.FG), t.msg);
                y = by - 6f;
            }
            ImGui.popFont();
        }
    }

    // ------------------------------------------------------------------ Shared Command Palette (Ctrl+P)

    public static final class CommandPalette {
        public record Entry(String id, String label, String badge, SemanticColor role, Runnable action) {}

        private static final List<Entry> ENTRIES = new ArrayList<>();
        private static final ImString QUERY = new ImString(128);
        private static boolean open = false;
        private static boolean focusInput = false;

        private CommandPalette() {}

        public static synchronized void register(Entry entry) {
            if (entry == null) return;
            ENTRIES.removeIf(e -> e.id.equals(entry.id));
            ENTRIES.add(entry);
        }

        public static boolean isOpen() { return open; }

        public static void toggle() {
            open = !open;
            if (open) {
                QUERY.set("");
                focusInput = true;
            }
        }

        public static void close() { open = false; }

        public static void draw() {
            if (!open) return;
            var vp = ImGui.getMainViewport();
            ImGui.setNextWindowPos(vp.getPosX() + (vp.getSizeX() - 520f) * 0.5f, vp.getPosY() + 72f);
            ImGui.setNextWindowSize(520f, 340f);
            int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove;
            if (ImGui.begin("##LiskovCmdPalette", flags)) {
                if (ImGui.isKeyPressed(ImGuiKey.Escape)) {
                    open = false;
                }
                MubTheme.textColored(MubTheme.ACCENT, ">");
                ImGui.sameLine();
                if (focusInput) {
                    ImGui.setKeyboardFocusHere();
                    focusInput = false;
                }
                ImGui.setNextItemWidth(-44f);
                ImGui.inputTextWithHint("##cmd_q", "Search Liskov / Mod commands...", QUERY);
                ImGui.sameLine();
                if (termButton("ESC", SemanticColor.INK_MUTED)) open = false;
                ImGui.separator();

                String q = QUERY.get().trim().toLowerCase(Locale.ROOT);
                List<Entry> snapshot;
                synchronized (CommandPalette.class) {
                    snapshot = List.copyOf(ENTRIES);
                }
                if (ImGui.beginChild("##cmd_hits", 0f, 0f, false)) {
                    for (Entry e : snapshot) {
                        if (!q.isEmpty() && !e.label.toLowerCase(Locale.ROOT).contains(q)) continue;
                        termBadge(e.badge, e.role);
                        ImGui.sameLine();
                        if (ImGui.selectable(e.label + "##cmd_" + e.id)) {
                            open = false;
                            if (e.action != null) e.action.run();
                        }
                    }
                }
                ImGui.endChild();
            }
            ImGui.end();
        }
    }
}
