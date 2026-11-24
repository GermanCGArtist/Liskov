package dev.liskov.ui.explorer;

import dev.liskov.core.CacheState;
import dev.liskov.core.DriveMount;
import dev.liskov.core.FavoritesStore;
import dev.liskov.core.LiskovDrives;
import dev.liskov.core.LiskovFileType;
import dev.liskov.core.LiskovFs;
import dev.liskov.core.LiskovNamespace;
import dev.liskov.core.LiskovPaths;
import dev.liskov.core.ReloadWatcher;
import dev.liskov.core.SemanticColor;
import dev.liskov.core.TrashManager;
import dev.liskov.core.sync.SyncWire;
import dev.liskov.core.vcs.VcsCommit;
import dev.liskov.core.vcs.VcsHead;
import dev.liskov.core.vcs.VcsStore;
import dev.liskov.net.LiskovReloadBus;
import dev.liskov.net.LiskovSyncClient;
import dev.liskov.ui.LiskovClientMod;
import dev.liskov.ui.host.InputMap;
import dev.liskov.ui.host.LiskovUiHost;
import dev.liskov.ui.host.UiLayer;
import dev.liskov.ui.menu.MenuOverviewRegistry;
import dev.liskov.ui.theme.MubTheme;
import dev.liskov.ui.theme.MubWidgets;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiDragDropFlags;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Multi-window Liskov File Explorer ({@code UiLayer} + embeddable workspace panel).
 * Supports Miller Columns / List / Grid views, Shift+Click / Ctrl+Click multi-selection,
 * standard PC keyboard shortcuts, right-click context menus, {@code LOCAL} + Server Drives,
 * cross-window drag-and-drop, Favorites, {@code .trash/}, and {@link InspectorPane}.
 */
public final class LiskovExplorer implements UiLayer {
    private static final String DND_PAYLOAD = "LISKOV_ITEM";
    private static final DateTimeFormatter LIST_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final LiskovExplorer INSTANCE = new LiskovExplorer();

    private final List<ExplorerWindowState> windows = new ArrayList<>();
    private final InspectorPane inspector = new InspectorPane();
    private final ImString newFolderName = new ImString(64);

    private boolean open = false;
    private int nextWindowId = 1;
    private int activeWindowId = 1;
    private ReloadWatcher watcher;
    private TrashManager trash;
    private FavoritesStore favorites;
    private VcsStore vcs;

    private record DragPayload(String driveId, List<String> relativePaths) {}
    private DragPayload pendingDrag;

    // Internal file clipboard for Ctrl+C / Ctrl+X / Ctrl+V
    private String clipDriveId;
    private final List<String> clipRelPaths = new ArrayList<>();
    private boolean clipCut = false;

    private LiskovExplorer() {
        windows.add(new ExplorerWindowState(nextWindowId++));
    }

    public static LiskovExplorer get() {
        return INSTANCE;
    }

    public void init(ReloadWatcher watcher) {
        this.watcher = watcher;
        LiskovFs fs = LiskovFs.get();
        try {
            this.trash = new TrashManager(fs.trashDir());
            this.favorites = FavoritesStore.loadOrCreate(fs.favoritesFile());
            this.vcs = new VcsStore(fs.vcsRootDir());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize LiskovExplorer stores", e);
        }

        MenuOverviewRegistry.get().register(new MenuOverviewRegistry.CardProvider() {
            @Override public String id() { return "liskov.overview"; }
            @Override public String title() { return "LISKOV DRIVES"; }
            @Override public SemanticColor accentRole() { return SemanticColor.FOCUS; }
            @Override public int order() { return 10; }
            @Override public boolean supports(MenuOverviewRegistry.Surface s) {
                return s == MenuOverviewRegistry.Surface.TITLE_SCREEN || s == MenuOverviewRegistry.Surface.PAUSE_MENU;
            }
            @Override public void drawCardBody(MenuOverviewRegistry.Surface s) {
                LiskovFs lfs = LiskovFs.get();
                int srvCount = lfs.drives().serverDrives().size();
                MubTheme.textColored(MubTheme.DIM, "1 LOCAL | " + srvCount + " Server Drive(s)");
                if (!favorites.favorites.isEmpty()) {
                    MubWidgets.sectionHeader("FAVORITES");
                    for (int i = 0; i < Math.min(3, favorites.favorites.size()); i++) {
                        var fav = favorites.favorites.get(i);
                        if (MubWidgets.termButton("* " + fav.label + "##ovf_" + i, SemanticColor.INK_MUTED)) {
                            jumpTo(fav.driveId, fav.relativePath);
                            setOpen(true);
                        }
                    }
                }
                if (MubWidgets.termButton("OPEN LISKOV (F8)", SemanticColor.FOCUS)) {
                    setOpen(true);
                }
                MubWidgets.tooltip("Open Liskov Explorer", "Browse local & server files, 3D previews, and snapshots.");
                ImGui.sameLine();
                if (MubWidgets.termButton("COPY ROOT", SemanticColor.INK_MUTED)) {
                    lfs.copyPathToClipboard(lfs.liskovRoot());
                    MubWidgets.ToastHost.push("Copied Liskov root path", SemanticColor.PLAYER);
                }
                MubWidgets.tooltip("Copy Root Folder Path", "Copy <gameDir>/liskov path to clipboard.");
            }
        });
    }

    public boolean isOpen() { return open; }

    public void setOpen(boolean open) {
        this.open = open;
        if (open && windows.isEmpty()) {
            windows.add(new ExplorerWindowState(nextWindowId++));
            activeWindowId = windows.get(0).windowId;
        }
        Minecraft mc = Minecraft.getInstance();
        if (open && mc != null && mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }
    }

    public void toggle() { setOpen(!open); }

    public void spawnSecondWindow() {
        if (windows.size() >= 6) {
            MubWidgets.ToastHost.push("Maximum 6 Liskov windows open", SemanticColor.WARN);
            return;
        }
        ExplorerWindowState w = new ExplorerWindowState(nextWindowId++);
        var servers = LiskovFs.get().drives().serverDrives();
        if (!servers.isEmpty()) {
            w.driveId = servers.get(0).driveId();
        } else {
            ExplorerWindowState active = activeWindow();
            if (active != null) w.viewMode = active.viewMode;
        }
        windows.add(w);
        activeWindowId = w.windowId;
        MubWidgets.ToastHost.push("Opened Window #" + w.windowId + " - drag files between windows", SemanticColor.FOCUS);
    }

    public void jumpTo(String driveId, String relPath) {
        ExplorerWindowState w = windows.get(0);
        w.driveId = driveId;
        w.viewingTrash = false;
        DriveMount d = LiskovFs.get().drives().get(driveId);
        if (d != null) {
            Path p = LiskovPaths.resolveInside(d.rootDir(), relPath);
            w.selectedPath = p;
            w.multiSelection.clear();
            w.multiSelection.add(p);
            w.selectionAnchor = p;
        }
    }

    private ExplorerWindowState activeWindow() {
        for (ExplorerWindowState w : windows) {
            if (w.windowId == activeWindowId) return w;
        }
        return windows.isEmpty() ? null : windows.get(0);
    }

    /**
     * Global key interception invoked by {@link dev.liskov.ui.host.HostCore#onKey} even when no ImGui layer is open.
     */
    public boolean handleGlobalKey(int key, int scancode, int action, int mods) {
        if (action != InputMap.GLFW_PRESS) return false;
        boolean ctrl = (mods & (InputMap.GLFW_MOD_CONTROL | InputMap.GLFW_MOD_SUPER)) != 0;
        if (ctrl && key == 80 /* P */) {
            MubWidgets.CommandPalette.toggle();
            Minecraft mc = Minecraft.getInstance();
            if (MubWidgets.CommandPalette.isOpen() && mc != null && mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed()) {
                mc.mouseHandler.releaseMouse();
            }
            return true;
        }
        if (LiskovClientMod.matchesExplorerKey(scancode) && !LiskovUiHost.imguiWantsTextInput()) {
            Minecraft mc = Minecraft.getInstance();
            boolean menuContext = mc != null && (mc.gui.screen() instanceof TitleScreen || mc.gui.screen() instanceof PauseScreen);
            if (open || menuContext) {
                toggle();
                return true;
            }
        }
        return false;
    }

    @Override public String id() { return "liskov.explorer"; }
    @Override public int order() { return 100; }

    @Override
    public boolean visible() {
        return open || FilePickerModal.isOpen() || MubWidgets.CommandPalette.isOpen();
    }

    @Override
    public boolean wantsKey(int key, int scancode, int action, int mods) {
        return open || FilePickerModal.isOpen() || MubWidgets.CommandPalette.isOpen() || LiskovUiHost.imguiWantsKeyboard();
    }

    @Override
    public void onKey(int key, int scancode, int action, int mods) {
        boolean isPress = action == InputMap.GLFW_PRESS;
        boolean isRepeat = action == InputMap.GLFW_REPEAT;
        if (!isPress && !isRepeat) return;

        boolean ctrl = (mods & (InputMap.GLFW_MOD_CONTROL | InputMap.GLFW_MOD_SUPER)) != 0;
        boolean shift = (mods & InputMap.GLFW_MOD_SHIFT) != 0;
        boolean alt = (mods & InputMap.GLFW_MOD_ALT) != 0;

        if (isPress && key == 256 /* Esc */) {
            if (MubWidgets.CommandPalette.isOpen()) { MubWidgets.CommandPalette.close(); return; }
            if (FilePickerModal.isOpen()) { FilePickerModal.close(); return; }
            ExplorerWindowState win = activeWindow();
            if (win != null && win.renamingPath != null) { win.renamingPath = null; return; }
            if (win != null && win.showShortcutsPopup) { win.showShortcutsPopup = false; return; }
            if (!LiskovUiHost.imguiWantsTextInput() && open) {
                if (win != null && !win.search.get().isEmpty()) {
                    win.search.set("");
                    return;
                }
                setOpen(false);
            }
            return;
        }

        if (!open || MubWidgets.CommandPalette.isOpen() || FilePickerModal.isOpen()) return;
        ExplorerWindowState win = activeWindow();
        if (win == null) return;

        if (isPress && ctrl && key == 70 /* Ctrl+F */) {
            win.focusSearchNextFrame = true;
            return;
        }

        if (LiskovUiHost.imguiWantsTextInput() || win.renamingPath != null) return;

        LiskovFs fs = LiskovFs.get();
        DriveMount drive = fs.drives().get(win.driveId);
        if (drive == null) drive = fs.drives().local();

        // Arrow up/down navigation (supports key hold repeat)
        if (key == 264 /* Down */ || key == 265 /* Up */) {
            stepSelection(win, drive, key == 264 ? 1 : -1, shift);
            return;
        }
        if (key == 259 /* Backspace */ || (alt && key == 263 /* Alt+Left */) || (isPress && key == 263 /* Left */)) {
            navigateUp(win);
            return;
        }
        if (!isPress) return;

        if (ctrl && !shift && key == 78 /* Ctrl+N */) { spawnSecondWindow(); return; }
        if (ctrl && shift && key == 78 /* Ctrl+Shift+N */) { win.focusNewFolderNextFrame = true; return; }
        if (ctrl && key == 87 /* Ctrl+W */) { closeWindow(win.windowId); return; }
        if (ctrl && key == 65 /* Ctrl+A */) {
            win.multiSelection.clear();
            win.multiSelection.addAll(win.activeEntries);
            if (!win.activeEntries.isEmpty()) {
                win.selectionAnchor = win.activeEntries.get(0);
                win.selectedPath = win.activeEntries.get(win.activeEntries.size() - 1);
            }
            return;
        }
        if (ctrl && key == 67 /* Ctrl+C */) { copySelection(win, drive, false); return; }
        if (ctrl && key == 88 /* Ctrl+X */) { copySelection(win, drive, true); return; }
        if (ctrl && key == 86 /* Ctrl+V */) { pasteClipboard(win, drive); return; }
        if (key == 294 /* F5 */ || (ctrl && key == 82 /* Ctrl+R */)) {
            if (watcher != null) {
                watcher.reloadNow();
                MubWidgets.ToastHost.push("ReloadWatcher scan complete", SemanticColor.MR);
            }
            return;
        }
        if (ctrl && key == 49 /* Ctrl+1 */) { win.viewMode = ExplorerWindowState.ViewMode.COLUMNS; return; }
        if (ctrl && key == 50 /* Ctrl+2 */) { win.viewMode = ExplorerWindowState.ViewMode.LIST; return; }
        if (ctrl && key == 51 /* Ctrl+3 */) { win.viewMode = ExplorerWindowState.ViewMode.GRID; return; }

        if (key == 291 /* F2 */ && !drive.isOffline() && win.selectedPath != null) {
            win.renamingPath = win.selectedPath;
            win.renameBuf.set(win.selectedPath.getFileName().toString());
            return;
        }
        if (key == 261 /* Delete */ && !drive.isOffline() && !win.viewingTrash) {
            trashSelection(win, fs);
            return;
        }
        if ((key == 257 /* Enter */ || key == 262 /* Right */) && win.selectedPath != null && Files.isDirectory(win.selectedPath)) {
            enterDirectory(win, win.selectedPath);
        }
    }

    @Override
    public void draw() {
        if (open) {
            for (int i = 0; i < windows.size(); i++) {
                ExplorerWindowState win = windows.get(i);
                ImBoolean pOpen = new ImBoolean(true);
                var vp = ImGui.getMainViewport();
                float w = Math.min(1120f, vp.getSizeX() - 48f);
                float h = Math.min(680f, vp.getSizeY() - 64f);
                float offsetX = Math.min(vp.getSizeX() - 280f, 24f + i * 48f);
                float offsetY = Math.min(vp.getSizeY() - 220f, 28f + i * 36f);
                ImGui.setNextWindowPos(vp.getPosX() + offsetX, vp.getPosY() + offsetY, ImGuiCond.FirstUseEver);
                ImGui.setNextWindowSize(w, h, ImGuiCond.FirstUseEver);

                if (ImGui.begin("Liskov Explorer #" + win.windowId + "###LiskovWin_" + win.windowId, pOpen, ImGuiWindowFlags.NoCollapse)) {
                    if (ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows)) {
                        activeWindowId = win.windowId;
                    }
                    drawWindowContents(win);
                }
                ImGui.end();
                if (!pOpen.get()) {
                    if (win.windowId == 1) setOpen(false);
                    else {
                        windows.remove(i);
                        i--;
                    }
                }
            }
        }
        FilePickerModal.draw();
        MubWidgets.CommandPalette.draw();
        MubWidgets.ToastHost.drawOverlay();
    }

    /** Embeddable render call for Manhattan's docked workspace {@code FILES} tab. */
    public void drawEmbedded() {
        if (!windows.isEmpty()) {
            drawWindowContents(windows.get(0));
        }
    }

    private void drawWindowContents(ExplorerWindowState win) {
        LiskovFs fs = LiskovFs.get();
        DriveMount drive = fs.drives().get(win.driveId);
        if (drive == null) {
            drive = fs.drives().local();
            win.driveId = drive.driveId();
        }
        Path activeRoot = resolveActiveRoot(fs, drive, win);

        // Row 1: Action Toolbar
        MubWidgets.termBadge(drive.isLocal() ? "LOCAL" : drive.displayName(),
                drive.isLocal() ? SemanticColor.FOCUS : SemanticColor.BOT);
        MubWidgets.tooltip("Active Drive", drive.rootDir().toString());
        ImGui.sameLine();
        if (MubWidgets.termButton("COPY PATH##cp_" + win.windowId, SemanticColor.INK_MUTED)) {
            fs.copyPathToClipboard(win.selectedPath != null ? win.selectedPath : (win.activeDir != null ? win.activeDir : activeRoot));
            MubWidgets.ToastHost.push("Copied path", SemanticColor.PLAYER);
        }
        MubWidgets.tooltip("Copy Path", "Copy current folder or selected file path to clipboard.");
        ImGui.sameLine();
        if (MubWidgets.termButton("OS EXPLORER##ex_" + win.windowId, SemanticColor.INK_MUTED)) {
            fs.openInExplorer(win.selectedPath != null ? win.selectedPath : (win.activeDir != null ? win.activeDir : activeRoot));
        }
        MubWidgets.tooltip("Open in OS File Explorer", "Open the selected location in Windows Explorer / Finder.");
        ImGui.sameLine();
        if (watcher != null && MubWidgets.termButton("RELOAD##rl_" + win.windowId, SemanticColor.INK_MUTED)) {
            watcher.reloadNow();
            MubWidgets.ToastHost.push("ReloadWatcher scan complete", SemanticColor.MR);
        }
        MubWidgets.tooltip("Reload Files (F5 / Ctrl+R)", "Scan disk immediately for external modifications.");
        if (watcher != null) {
            ImGui.sameLine();
            if (MubWidgets.termToggle("aw_" + win.windowId, "WATCH", watcher.isEnabled(), SemanticColor.MR)) {
                watcher.setEnabled(!watcher.isEnabled());
            }
            MubWidgets.tooltip("Auto-Watch", "Automatically detect file changes on disk every 2 seconds.");
        }
        ImGui.sameLine();
        if (MubWidgets.termButton("+ NEW WINDOW##nw_" + win.windowId, SemanticColor.FOCUS)) {
            spawnSecondWindow();
        }
        MubWidgets.tooltip("Open Second Window (Ctrl+N)", "Open an extra Explorer window for side-by-side drag & drop between drives.");
        ImGui.sameLine();
        if (MubWidgets.termToggle("mc_" + win.windowId, "MENU CARD", MubTheme.config().showMenuOverview, SemanticColor.INK_MUTED)) {
            MubTheme.config().showMenuOverview = !MubTheme.config().showMenuOverview;
            MubTheme.saveToDisk();
        }
        MubWidgets.tooltip("Toggle Title/Pause Menu Card", "Show or hide the Liskov overview card on the Title Screen & Pause Menu.");
        ImGui.sameLine();
        if (MubWidgets.termButton("THEME: " + MubTheme.preset().displayName + "##th_" + win.windowId, SemanticColor.META)) {
            MubTheme.cyclePreset();
        }
        MubWidgets.tooltip("Cycle Theme Preset", "Switch between Synth Terminal, Gruvbox Pixel, Tokyo Night, and Drafting Paper.");
        ImGui.sameLine();
        if (MubWidgets.termButton("KEYS##hk_" + win.windowId, win.showShortcutsPopup ? SemanticColor.FOCUS : SemanticColor.INK_MUTED)) {
            win.showShortcutsPopup = !win.showShortcutsPopup;
        }
        MubWidgets.tooltip("Keyboard Shortcuts", "View all supported Explorer shortcuts & mouse gestures.");

        // Row 2: Path Bar
        Path displayPath = win.selectedPath != null ? win.selectedPath : (win.activeDir != null ? win.activeDir : activeRoot);
        MubTheme.textColored(MubTheme.DIM, displayPath.toString());
        MubWidgets.tooltip("Current Location", displayPath.toAbsolutePath().normalize().toString());

        // Offline server banner
        if (drive.isOffline()) {
            MubWidgets.termBadge("OFFLINE | READ-ONLY", SemanticColor.INK_MUTED);
            ImGui.sameLine();
            MubTheme.textColored(MubTheme.DIM, "Server ID " + drive.driveId() + " | Browse cached or drag to LOCAL.");
        }

        // Non-destructive Peer Conflict Banner
        for (LiskovReloadBus.ConflictNotice cn : LiskovSyncClient.get().activeConflicts().values()) {
            MubWidgets.termBadge("SYNC CONFLICT", SemanticColor.WARN);
            ImGui.sameLine();
            MubTheme.textColored(MubTheme.FG, cn.relativePath() + " updated to v" + cn.serverVersion() + " by " + cn.authorName());
            ImGui.sameLine();
            if (MubWidgets.termButton("USE SERVER##us_" + cn.repoId(), SemanticColor.WARN)) {
                LiskovSyncClient.get().setDirty(cn.repoId(), false);
                LiskovSyncClient.get().clearConflict(cn.repoId());
                for (SyncWire.FolderSummary fsSum : LiskovSyncClient.get().foldersOf(cn.serverId())) {
                    for (SyncWire.RepoEntry e : fsSum.entries) {
                        if (e.repoId.equals(cn.repoId())) LiskovSyncClient.get().requestDownload(e);
                    }
                }
            }
            ImGui.sameLine();
            if (MubWidgets.termButton("DISMISS##dc_" + cn.repoId(), SemanticColor.INK_MUTED)) {
                LiskovSyncClient.get().clearConflict(cn.repoId());
            }
        }

        // Row 3: Search, File-Type Filter Chips & View Mode Switcher
        if (win.focusSearchNextFrame) {
            ImGui.setKeyboardFocusHere();
            win.focusSearchNextFrame = false;
        }
        ImGui.setNextItemWidth(175f);
        ImGui.inputTextWithHint("##search_" + win.windowId, "Filter (Ctrl+F)...", win.search);
        MubWidgets.tooltip("Filter Files", "Type to filter visible entries by filename (Shortcut: Ctrl+F, Esc clears).");
        for (LiskovFileType ft : fs.fileTypes().all()) {
            if (ft.primaryExtension().isEmpty() || ft == dev.liskov.core.FileTypeRegistry.CONFIG || ft == dev.liskov.core.FileTypeRegistry.LOG) continue;
            ImGui.sameLine();
            boolean active = ft.id().equals(win.filterFileTypeId);
            if (MubWidgets.termButton(ft.monogram() + "##ft_" + win.windowId + "_" + ft.id(),
                    active ? ft.colorRole() : SemanticColor.INK_MUTED)) {
                win.filterFileTypeId = active ? null : ft.id();
            }
            MubWidgets.tooltip("Filter: " + ft.displayName(),
                    "Toggle filter for " + ft.primaryExtension() + " files.");
        }
        ImGui.sameLine();
        if (MubWidgets.termButton("COLS##v1_" + win.windowId,
                win.viewMode == ExplorerWindowState.ViewMode.COLUMNS ? SemanticColor.FOCUS : SemanticColor.INK_MUTED)) {
            win.viewMode = ExplorerWindowState.ViewMode.COLUMNS;
        }
        MubWidgets.tooltip("Miller Columns View (Ctrl+1)", "Multi-column folder tree navigation.");
        ImGui.sameLine();
        if (MubWidgets.termButton("LIST##v2_" + win.windowId,
                win.viewMode == ExplorerWindowState.ViewMode.LIST ? SemanticColor.FOCUS : SemanticColor.INK_MUTED)) {
            win.viewMode = ExplorerWindowState.ViewMode.LIST;
        }
        MubWidgets.tooltip("Details List View (Ctrl+2)", "Rows with type badge, version, size, and modified timestamp.");
        ImGui.sameLine();
        if (MubWidgets.termButton("GRID##v3_" + win.windowId,
                win.viewMode == ExplorerWindowState.ViewMode.GRID ? SemanticColor.FOCUS : SemanticColor.INK_MUTED)) {
            win.viewMode = ExplorerWindowState.ViewMode.GRID;
        }
        MubWidgets.tooltip("Icon Grid View (Ctrl+3)", "Large visual tiles for folders and schematics.");

        ImGui.separator();
        float bodyH = ImGui.getContentRegionAvailY();

        // 1. Left Drives Sidebar
        if (ImGui.beginChild("##sidebar_" + win.windowId, 185f, bodyH, true)) {
            drawDrivesSidebar(win, fs);
        }
        ImGui.endChild();
        ImGui.sameLine();

        // 2. Center Miller Columns / List / Grid
        float inspW = (win.windowId == activeWindowId || windows.size() == 1) ? 375f : 0f;
        float centerW = Math.max(240f, ImGui.getContentRegionAvailX() - inspW - (inspW > 0 ? 8f : 0f));
        int centerFlags = win.viewMode == ExplorerWindowState.ViewMode.COLUMNS
                ? ImGuiWindowFlags.HorizontalScrollbar
                : ImGuiWindowFlags.None;
        if (ImGui.beginChild("##center_" + win.windowId, centerW, bodyH, true, centerFlags)) {
            if (win.viewingTrash) {
                drawTrashView(win);
            } else if (win.viewMode == ExplorerWindowState.ViewMode.COLUMNS) {
                drawMillerColumns(win, fs, drive, activeRoot, bodyH - 16f);
            } else if (win.viewMode == ExplorerWindowState.ViewMode.LIST) {
                Path curDir = currentDirectory(win, activeRoot);
                drawDirectoryListView(win, fs, drive, activeRoot, curDir);
            } else {
                Path curDir = currentDirectory(win, activeRoot);
                drawDirectoryGridView(win, fs, drive, activeRoot, curDir);
            }
        }
        ImGui.endChild();

        // 3. Right Inspector (AlwaysVerticalScrollbar prevents scrollbar width oscillation / window shake)
        if (inspW > 0f) {
            ImGui.sameLine();
            if (ImGui.beginChild("##insp_" + win.windowId, inspW, bodyH, true, ImGuiWindowFlags.AlwaysVerticalScrollbar)) {
                inspector.draw(win, drive, vcs, favorites, trash, watcher);
            }
            ImGui.endChild();
        }

        drawRenameModal(win, drive);
        drawShortcutsModal(win);
    }

    private void drawDrivesSidebar(ExplorerWindowState win, LiskovFs fs) {
        MubWidgets.sectionHeader("FAVORITES");
        for (int i = 0; i < favorites.favorites.size(); i++) {
            var fav = favorites.favorites.get(i);
            if (ImGui.selectable("* " + fav.label + "##favs_" + win.windowId + "_" + i, false)) {
                jumpTo(fav.driveId, fav.relativePath);
            }
            MubWidgets.tooltip("Jump to Favorite", fav.driveId + ":/" + fav.relativePath);
        }
        ImGui.spacing();

        MubWidgets.sectionHeader("DRIVE: LOCAL");
        for (LiskovNamespace ns : fs.namespaces()) {
            boolean sel = LiskovDrives.LOCAL_DRIVE_ID.equals(win.driveId) && !win.viewingTrash && ns.id().equals(win.namespaceId);
            MubWidgets.termBadge(ns.badge(), ns.accentRole());
            ImGui.sameLine();
            if (ImGui.selectable(ns.displayName() + "##ns_" + win.windowId + "_" + ns.id(), sel)) {
                win.driveId = LiskovDrives.LOCAL_DRIVE_ID;
                win.namespaceId = ns.id();
                win.viewingTrash = false;
                win.columnDirs.clear();
                win.selectedPath = null;
                win.multiSelection.clear();
                win.selectionAnchor = null;
            }
            MubWidgets.tooltip(ns.displayName(), "Local workspace namespace (drop items here to copy/move).");
            acceptDropTarget(fs.drives().local(), ns.id());
        }
        if (ImGui.selectable(".trash##tr_" + win.windowId, win.viewingTrash)) {
            win.driveId = LiskovDrives.LOCAL_DRIVE_ID;
            win.viewingTrash = true;
            win.selectedPath = null;
            win.multiSelection.clear();
        }
        MubWidgets.tooltip("Recoverable Trash", "View or restore soft-deleted files in .trash/.");
        ImGui.spacing();

        MubWidgets.sectionHeader("SERVER DRIVES");
        List<DriveMount> srvs = fs.drives().serverDrives();
        if (srvs.isEmpty()) {
            MubTheme.textColored(MubTheme.GUTTER, "(Join a Liskov server)");
        }
        for (DriveMount d : srvs) {
            boolean sel = d.driveId().equals(win.driveId) && !win.viewingTrash;
            boolean online = !d.isOffline();
            MubWidgets.termBadge(online ? "ON" : "OF", online ? SemanticColor.MR : SemanticColor.INK_MUTED);
            ImGui.sameLine();
            if (ImGui.selectable(d.displayName() + "##srv_" + win.windowId + "_" + d.driveId(), sel)) {
                win.driveId = d.driveId();
                win.viewingTrash = false;
                win.columnDirs.clear();
                win.selectedPath = null;
                win.multiSelection.clear();
                win.selectionAnchor = null;
            }
            MubWidgets.tooltip(d.displayName(),
                    online ? "Connected server drive (drop items to copy/sync)." : "Offline cached server drive (read-only).");
            acceptDropTarget(d, "");
        }
    }

    private void drawMillerColumns(ExplorerWindowState win, LiskovFs fs, DriveMount drive, Path activeRoot, float colH) {
        List<Path> colRoots = new ArrayList<>();
        colRoots.add(activeRoot);
        colRoots.addAll(win.columnDirs);

        for (int c = 0; c < colRoots.size(); c++) {
            Path dir = colRoots.get(c);
            if (!Files.isDirectory(dir)) continue;
            if (c > 0) ImGui.sameLine();
            if (ImGui.beginChild("##col_" + win.windowId + "_" + c, 224f, colH, true)) {
                String title = c == 0 ? (drive.isLocal() ? win.namespaceId.toUpperCase(Locale.ROOT) : drive.displayName())
                        : dir.getFileName().toString();
                MubTheme.textColored(MubTheme.DIM, title);
                ImGui.separator();

                List<Path> entries = listSorted(dir, win.search.get().trim().toLowerCase(Locale.ROOT), win.filterFileTypeId, fs);
                if (c == colRoots.size() - 1) {
                    win.activeDir = dir;
                    win.activeEntries = entries;
                }

                for (Path entry : entries) {
                    boolean isDir = Files.isDirectory(entry);
                    String name = entry.getFileName().toString();
                    boolean sel = isSelected(win, entry) || (c < win.columnDirs.size() && entry.equals(win.columnDirs.get(c)));
                    String rel = safeRel(drive, entry);

                    if (isDir) {
                        MubWidgets.folderIcon(MubTheme.META, 14f);
                        ImGui.sameLine();
                        if (ImGui.selectable(name + "##d_" + c + "_" + name, sel)) {
                            ImGuiIO io = ImGui.getIO();
                            if (io.getKeyShift() || io.getKeyCtrl() || io.getKeySuper()) {
                                applySelectionClick(win, drive, entry, entries, true);
                            } else {
                                while (win.columnDirs.size() > c) win.columnDirs.remove(win.columnDirs.size() - 1);
                                win.columnDirs.add(entry);
                                applySelectionClick(win, drive, entry, entries, true);
                            }
                        }
                        float rx1 = ImGui.getItemRectMaxX();
                        float ryMid = (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) * 0.5f;
                        MubWidgets.drawChevronRightAt(ImGui.getWindowDrawList(), rx1 - 8f, ryMid, 3.5f, MubTheme.u32(MubTheme.GUTTER));
                        MubWidgets.tooltip("Folder: " + name, "Click: Open column  |  Shift/Ctrl+Click: Multi-select  |  Right-click: Menu");
                        drawEntryDragAndMenu(win, drive, entry, rel, true, entries);
                        acceptDropTarget(drive, rel);
                    } else {
                        LiskovFileType ft = fs.fileTypes().detect(entry);
                        MubWidgets.fileTypeBadge(ft, false);
                        ImGui.sameLine();
                        VcsHead head = vcs.loadHead(drive.driveId(), VcsStore.repoIdFor(rel));
                        String suffix = head.latestVersion > 0 ? " [v" + head.latestVersion + "]" : "";
                        if (ImGui.selectable(name + suffix + "##f_" + c + "_" + name, sel)) {
                            while (win.columnDirs.size() > c) win.columnDirs.remove(win.columnDirs.size() - 1);
                            applySelectionClick(win, drive, entry, entries, false);
                        }
                        MubWidgets.tooltip(name + suffix,
                                ft.displayName() + "  |  Shift/Ctrl+Click: Multi-select  |  Drag to copy/move");
                        drawEntryDragAndMenu(win, drive, entry, rel, false, entries);
                    }
                }
                if (!drive.isOffline() && c == colRoots.size() - 1) {
                    ImGui.separator();
                    drawNewFolderBar(win, dir, c);
                }
            }
            ImGui.endChild();
            acceptDropTarget(drive, safeRel(drive, dir));
        }
    }

    private void drawDirectoryListView(
            ExplorerWindowState win, LiskovFs fs, DriveMount drive, Path activeRoot, Path dir
    ) {
        float availW = Math.max(360f, ImGui.getContentRegionAvailX());
        List<Path> entries = listSorted(dir, win.search.get().trim().toLowerCase(Locale.ROOT), win.filterFileTypeId, fs);
        win.activeDir = dir;
        win.activeEntries = entries;

        // Column header strip
        float baseX = ImGui.getCursorScreenPosX();
        float colTypeX = baseX + availW - 220f;
        float colSizeX = baseX + availW - 155f;
        float colDateX = baseX + availW - 95f;

        MubTheme.push(MubTheme.small());
        MubTheme.textColored(MubTheme.GUTTER, "NAME");
        if (colTypeX > baseX + 120f) {
            ImGui.sameLine();
            ImGui.setCursorScreenPos(colTypeX, ImGui.getCursorScreenPosY());
            MubTheme.textColored(MubTheme.GUTTER, "TYPE");
            ImGui.sameLine();
            ImGui.setCursorScreenPos(colSizeX, ImGui.getCursorScreenPosY());
            MubTheme.textColored(MubTheme.GUTTER, "SIZE");
            ImGui.sameLine();
            ImGui.setCursorScreenPos(colDateX, ImGui.getCursorScreenPosY());
            MubTheme.textColored(MubTheme.GUTTER, "MODIFIED");
        }
        ImGui.popFont();
        ImGui.separator();

        if (!win.columnDirs.isEmpty()) {
            if (ImGui.selectable("..  (Parent Folder)##list_up", false, 0, availW, 20f)) {
                navigateUp(win);
            }
            MubWidgets.tooltip("Go Up (Backspace / Alt+Left)", "Return to parent directory.");
        }

        ImDrawList dl = ImGui.getWindowDrawList();
        for (int i = 0; i < entries.size(); i++) {
            Path entry = entries.get(i);
            boolean isDir = Files.isDirectory(entry);
            String name = entry.getFileName().toString();
            String rel = safeRel(drive, entry);
            boolean sel = isSelected(win, entry);

            if (ImGui.selectable("##list_row_" + win.windowId + "_" + i, sel, 0, availW, 22f)) {
                applySelectionClick(win, drive, entry, entries, isDir);
            }
            if (isDir && ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                enterDirectory(win, entry);
            }

            float rx0 = ImGui.getItemRectMinX();
            float ry0 = ImGui.getItemRectMinY();
            float rx1 = ImGui.getItemRectMaxX();
            float ry1 = ImGui.getItemRectMaxY();
            float midY = (ry0 + ry1) * 0.5f;

            LiskovFileType ft = isDir ? null : fs.fileTypes().detect(entry);
            if (isDir) {
                MubWidgets.drawFolderIconAt(dl, rx0 + 4f, midY - 6f, 15f, 12f,
                        MubTheme.u32(MubTheme.META, 0.22f), MubTheme.u32(MubTheme.META));
            } else {
                int col = MubTheme.colorFor(ft.colorRole());
                dl.addRectFilled(rx0 + 3f, midY - 8f, rx0 + 24f, midY + 8f, MubTheme.u32(col, 0.16f));
                dl.addRect(rx0 + 3f, midY - 8f, rx0 + 24f, midY + 8f, MubTheme.u32(col));
                MubTheme.push(MubTheme.monoS());
                ImVec2 ts = ImGui.calcTextSize(ft.monogram());
                dl.addText(rx0 + 3f + (21f - ts.x) * 0.5f, midY - ts.y * 0.5f, MubTheme.u32(col), ft.monogram());
                ImGui.popFont();
            }

            String label = name;
            if (!isDir) {
                VcsHead head = vcs.loadHead(drive.driveId(), VcsStore.repoIdFor(rel));
                if (head.latestVersion > 0) label += " [v" + head.latestVersion + "]";
            }
            dl.pushClipRect(rx0 + 28f, ry0, Math.max(rx0 + 60f, colTypeX - 8f), ry1, true);
            dl.addText(rx0 + 30f, ry0 + 3f, MubTheme.u32(sel ? MubTheme.FG : MubTheme.FG), label);
            dl.popClipRect();

            if (colTypeX > rx0 + 120f) {
                String typeStr = isDir ? "DIR" : ft.monogram();
                String sizeStr = isDir ? "--" : formatSize(safeSize(entry));
                String dateStr = formatDate(entry);
                MubTheme.push(MubTheme.small());
                dl.addText(colTypeX, ry0 + 4f, MubTheme.u32(isDir ? MubTheme.META : MubTheme.colorFor(ft.colorRole())), typeStr);
                dl.addText(colSizeX, ry0 + 4f, MubTheme.u32(MubTheme.DIM), sizeStr);
                dl.addText(colDateX, ry0 + 4f, MubTheme.u32(MubTheme.GUTTER), dateStr);
                ImGui.popFont();
            }

            MubWidgets.tooltip(label, isDir
                    ? "Double-click or Enter: Open  |  Shift/Ctrl+Click: Multi-select"
                    : ft.displayName() + "  |  " + formatSize(safeSize(entry)) + "  |  Drag to transfer");
            drawEntryDragAndMenu(win, drive, entry, rel, isDir, entries);
            if (isDir) acceptDropTarget(drive, rel);
        }

        if (!drive.isOffline()) {
            ImGui.separator();
            drawNewFolderBar(win, dir, 999);
        }
    }

    private void drawDirectoryGridView(
            ExplorerWindowState win, LiskovFs fs, DriveMount drive, Path activeRoot, Path dir
    ) {
        List<Path> entries = listSorted(dir, win.search.get().trim().toLowerCase(Locale.ROOT), win.filterFileTypeId, fs);
        win.activeDir = dir;
        win.activeEntries = entries;

        float availW = Math.max(160f, ImGui.getContentRegionAvailX());
        float tileW = 136f;
        float tileH = 104f;
        float gap = 8f;
        int cols = Math.max(1, (int) ((availW + gap) / (tileW + gap)));

        if (!win.columnDirs.isEmpty()) {
            if (MubWidgets.termButton(".. PARENT FOLDER##grid_up", SemanticColor.INK_MUTED)) {
                navigateUp(win);
            }
            MubWidgets.tooltip("Go Up (Backspace)", "Return to parent directory.");
            ImGui.separator();
        }

        ImDrawList dl = ImGui.getWindowDrawList();
        for (int i = 0; i < entries.size(); i++) {
            int colIdx = i % cols;
            if (colIdx > 0) ImGui.sameLine(0f, gap);

            Path entry = entries.get(i);
            boolean isDir = Files.isDirectory(entry);
            String name = entry.getFileName().toString();
            String rel = safeRel(drive, entry);
            boolean sel = isSelected(win, entry);

            ImGui.invisibleButton("##grid_tile_" + win.windowId + "_" + i, tileW, tileH);
            if (ImGui.isItemClicked(ImGuiMouseButton.Left)) {
                applySelectionClick(win, drive, entry, entries, isDir);
            }
            boolean hovered = ImGui.isItemHovered();
            if (isDir && hovered && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                enterDirectory(win, entry);
            }

            float rx0 = ImGui.getItemRectMinX();
            float ry0 = ImGui.getItemRectMinY();
            float rx1 = ImGui.getItemRectMaxX();
            float ry1 = ImGui.getItemRectMaxY();

            int fill = MubTheme.u32(sel ? MubTheme.SEL : (hovered ? MubTheme.SURFACE : MubTheme.PANEL2));
            int border = MubTheme.u32(sel ? MubTheme.ACCENT : (hovered ? MubTheme.BORDER_HI : MubTheme.LINE));
            dl.addRectFilled(rx0, ry0, rx1, ry1, fill);
            dl.addRect(rx0, ry0, rx1, ry1, border, 0f, 0, sel ? 1.5f : 1.0f);

            LiskovFileType ft = isDir ? null : fs.fileTypes().detect(entry);
            if (isDir) {
                float iw = 44f, ih = 34f;
                MubWidgets.drawFolderIconAt(dl, rx0 + (tileW - iw) * 0.5f, ry0 + 16f, iw, ih,
                        MubTheme.u32(MubTheme.META, 0.20f), MubTheme.u32(MubTheme.META));
                MubTheme.push(MubTheme.small());
                ImVec2 tagSz = ImGui.calcTextSize("FOLDER");
                dl.addText(rx0 + (tileW - tagSz.x) * 0.5f, ry0 + 55f, MubTheme.u32(MubTheme.GUTTER), "FOLDER");
                ImGui.popFont();
            } else {
                int c = MubTheme.colorFor(ft.colorRole());
                float bw = 42f, bh = 36f;
                float bx = rx0 + (tileW - bw) * 0.5f;
                float by = ry0 + 14f;
                dl.addRectFilled(bx, by, bx + bw, by + bh, MubTheme.u32(c, 0.16f));
                dl.addRect(bx, by, bx + bw, by + bh, MubTheme.u32(c), 0f, 0, 1.5f);
                MubTheme.push(MubTheme.uiBold());
                ImVec2 ms = ImGui.calcTextSize(ft.monogram());
                dl.addText(bx + (bw - ms.x) * 0.5f, by + (bh - ms.y) * 0.5f, MubTheme.u32(c), ft.monogram());
                ImGui.popFont();

                VcsHead head = vcs.loadHead(drive.driveId(), VcsStore.repoIdFor(rel));
                if (head.latestVersion > 0) {
                    String vs = "v" + head.latestVersion;
                    MubTheme.push(MubTheme.small());
                    ImVec2 vsz = ImGui.calcTextSize(vs);
                    dl.addText(rx1 - vsz.x - 6f, ry0 + 5f, MubTheme.u32(MubTheme.ACCENT), vs);
                    ImGui.popFont();
                }

                MubTheme.push(MubTheme.small());
                String szTxt = formatSize(safeSize(entry));
                ImVec2 ssz = ImGui.calcTextSize(szTxt);
                dl.addText(rx0 + (tileW - ssz.x) * 0.5f, ry0 + 55f, MubTheme.u32(MubTheme.GUTTER), szTxt);
                ImGui.popFont();
            }

            // Clipped title near bottom
            String clipName = clipText(name, tileW - 14f);
            ImVec2 nsz = ImGui.calcTextSize(clipName);
            dl.pushClipRect(rx0 + 4f, ry0 + 70f, rx1 - 4f, ry1 - 4f, true);
            dl.addText(rx0 + Math.max(6f, (tileW - nsz.x) * 0.5f), ry0 + 74f, MubTheme.u32(MubTheme.FG), clipName);
            dl.popClipRect();

            MubWidgets.tooltip(name, isDir
                    ? "Folder  |  Double-click or Enter: Open  |  Shift/Ctrl+Click: Select"
                    : ft.displayName() + "  |  " + formatSize(safeSize(entry)) + "  |  " + formatDate(entry));
            drawEntryDragAndMenu(win, drive, entry, rel, isDir, entries);
            if (isDir) acceptDropTarget(drive, rel);
        }

        if (!drive.isOffline()) {
            ImGui.separator();
            drawNewFolderBar(win, dir, 998);
        }
    }

    private void drawNewFolderBar(ExplorerWindowState win, Path dir, int suffix) {
        if (win.focusNewFolderNextFrame) {
            ImGui.setKeyboardFocusHere();
            win.focusNewFolderNextFrame = false;
        }
        ImGui.setNextItemWidth(138f);
        ImGui.inputTextWithHint("##nf_" + win.windowId + "_" + suffix, "New folder...", newFolderName);
        MubWidgets.tooltip("New Folder (Ctrl+Shift+N)", "Type a name and click + to create a subfolder.");
        ImGui.sameLine();
        if (MubWidgets.termButton("+##nfb_" + win.windowId + "_" + suffix, SemanticColor.INK_MUTED) && !newFolderName.get().isBlank()) {
            try {
                Files.createDirectories(dir.resolve(LiskovPaths.safeName(newFolderName.get())));
                newFolderName.set("");
            } catch (IOException ignored) {}
        }
    }

    private void drawEntryDragAndMenu(
            ExplorerWindowState win, DriveMount drive, Path entry, String rel, boolean isDir, List<Path> entries
    ) {
        if (ImGui.beginDragDropSource(ImGuiDragDropFlags.None)) {
            List<String> dragged = new ArrayList<>();
            if (win.multiSelection.contains(entry) && win.multiSelection.size() > 1) {
                for (Path p : win.multiSelection) {
                    String pr = safeRel(drive, p);
                    if (!pr.isEmpty()) dragged.add(pr);
                }
            } else if (!rel.isEmpty()) {
                dragged.add(rel);
            }
            pendingDrag = new DragPayload(drive.driveId(), dragged);
            ImGui.setDragDropPayload(DND_PAYLOAD, rel);
            ImGui.textUnformatted(dragged.size() > 1 ? dragged.size() + " items" : entry.getFileName().toString());
            ImGui.endDragDropSource();
        }

        if (ImGui.beginPopupContextItem("##ctx_" + win.windowId + "_" + rel)) {
            if (!isSelected(win, entry)) {
                win.multiSelection.clear();
                win.multiSelection.add(entry);
                win.selectionAnchor = entry;
                win.selectedPath = entry;
            }
            if (isDir && ImGui.menuItem("Open Folder (Enter)")) {
                enterDirectory(win, entry);
            }
            if (ImGui.menuItem("Copy (Ctrl+C)")) copySelection(win, drive, false);
            if (!drive.isOffline() && ImGui.menuItem("Cut (Ctrl+X)")) copySelection(win, drive, true);
            if (!drive.isOffline() && !clipRelPaths.isEmpty() && ImGui.menuItem("Paste Here (Ctrl+V)")) {
                if (isDir) pasteIntoDir(drive, entry);
                else pasteClipboard(win, drive);
            }
            if (!drive.isOffline() && ImGui.menuItem("Rename (F2)")) {
                win.renamingPath = entry;
                win.renameBuf.set(entry.getFileName().toString());
            }
            if (ImGui.menuItem("Copy Path")) {
                LiskovFs.get().copyPathToClipboard(entry);
                MubWidgets.ToastHost.push("Copied path", SemanticColor.PLAYER);
            }
            if (!drive.isOffline() && !win.viewingTrash && ImGui.menuItem("Move to .trash (Del)")) {
                trashSelection(win, LiskovFs.get());
            }
            ImGui.endPopup();
        }
    }

    private void drawTrashView(ExplorerWindowState win) {
        MubWidgets.sectionHeader(".TRASH (RECOVERABLE)");
        List<TrashManager.TrashedItem> items = trash.list();
        if (items.isEmpty()) {
            MubTheme.textColored(MubTheme.GUTTER, "Trash is empty.");
            return;
        }
        for (TrashManager.TrashedItem item : items) {
            MubTheme.textColored(MubTheme.FG, item.fileName);
            ImGui.sameLine();
            MubTheme.textColored(MubTheme.GUTTER, "(" + item.originalPath + ")");
            ImGui.sameLine();
            if (MubWidgets.termButton("RESTORE##tr_" + item.id, SemanticColor.MR)) {
                try {
                    trash.restore(item.id);
                    MubWidgets.ToastHost.push("Restored " + item.fileName, SemanticColor.MR);
                } catch (Exception e) {
                    MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
                }
            }
            MubWidgets.tooltip("Restore", "Move item back to its original folder.");
        }
    }

    private void drawRenameModal(ExplorerWindowState win, DriveMount drive) {
        if (win.renamingPath == null) return;
        ImGui.openPopup("Rename Item###LiskovRename");
        if (ImGui.beginPopupModal("Rename Item###LiskovRename", ImGuiWindowFlags.AlwaysAutoResize)) {
            MubTheme.textColored(MubTheme.DIM, "New name for " + win.renamingPath.getFileName() + ":");
            ImGui.setNextItemWidth(260f);
            boolean submit = ImGui.inputText("##rename_in", win.renameBuf, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue);
            if (submit || MubWidgets.termButton("RENAME", SemanticColor.FOCUS)) {
                String next = LiskovPaths.safeName(win.renameBuf.get());
                Path target = win.renamingPath.resolveSibling(next);
                try {
                    if (!target.equals(win.renamingPath)) {
                        Files.move(win.renamingPath, target);
                        win.selectedPath = target;
                        win.multiSelection.remove(win.renamingPath);
                        win.multiSelection.add(target);
                        MubWidgets.ToastHost.push("Renamed to " + next, SemanticColor.MR);
                    }
                } catch (Exception e) {
                    MubWidgets.ToastHost.push("Rename failed: " + e.getMessage(), SemanticColor.DANGER);
                }
                win.renamingPath = null;
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (MubWidgets.termButton("CANCEL", SemanticColor.INK_MUTED)) {
                win.renamingPath = null;
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private void drawShortcutsModal(ExplorerWindowState win) {
        if (!win.showShortcutsPopup) return;
        ImGui.openPopup("Liskov Shortcuts###LiskovKeys");
        if (ImGui.beginPopupModal("Liskov Shortcuts###LiskovKeys", ImGuiWindowFlags.AlwaysAutoResize)) {
            MubWidgets.sectionHeader("GLOBAL & NAVIGATION");
            MubTheme.textColored(MubTheme.FG, "F8 (rebindable)   Toggle Liskov Explorer");
            MubTheme.textColored(MubTheme.FG, "Ctrl+P            Open Command Palette");
            MubTheme.textColored(MubTheme.FG, "Up / Down         Move selection (Shift+Up/Down extends)");
            MubTheme.textColored(MubTheme.FG, "Enter / DblClick  Open folder");
            MubTheme.textColored(MubTheme.FG, "Backspace / Left  Go to parent folder");
            ImGui.spacing();
            MubWidgets.sectionHeader("FILES & WINDOWS");
            MubTheme.textColored(MubTheme.FG, "Click / Ctrl+Clk  Select single / toggle multi-select");
            MubTheme.textColored(MubTheme.FG, "Shift+Click       Range select from anchor");
            MubTheme.textColored(MubTheme.FG, "Ctrl+A            Select all in active folder");
            MubTheme.textColored(MubTheme.FG, "Ctrl+C / X / V    Copy / Cut / Paste selected items");
            MubTheme.textColored(MubTheme.FG, "F2 / Del          Rename / Move selected to .trash/");
            MubTheme.textColored(MubTheme.FG, "Ctrl+N / Ctrl+W   New Explorer window / Close window");
            MubTheme.textColored(MubTheme.FG, "Ctrl+Shift+N      Focus New Folder box");
            MubTheme.textColored(MubTheme.FG, "Ctrl+F / F5       Focus Filter / Force Reload");
            MubTheme.textColored(MubTheme.FG, "Ctrl+1 / 2 / 3    Columns / List / Grid view");
            ImGui.spacing();
            if (MubWidgets.termButton("CLOSE (Esc)", SemanticColor.FOCUS)) {
                win.showShortcutsPopup = false;
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private static boolean isSelected(ExplorerWindowState win, Path entry) {
        return entry.equals(win.selectedPath) || win.multiSelection.contains(entry);
    }

    private void applySelectionClick(
            ExplorerWindowState win, DriveMount drive, Path entry, List<Path> ordered, boolean isDir
    ) {
        ImGuiIO io = ImGui.getIO();
        boolean shift = io.getKeyShift();
        boolean ctrl = io.getKeyCtrl() || io.getKeySuper();
        if (shift && win.selectionAnchor != null && ordered != null) {
            int a = ordered.indexOf(win.selectionAnchor);
            int b = ordered.indexOf(entry);
            if (a >= 0 && b >= 0) {
                int lo = Math.min(a, b);
                int hi = Math.max(a, b);
                if (!ctrl) win.multiSelection.clear();
                for (int i = lo; i <= hi; i++) win.multiSelection.add(ordered.get(i));
                win.selectedPath = entry;
            } else {
                win.multiSelection.clear();
                win.multiSelection.add(entry);
                win.selectionAnchor = entry;
                win.selectedPath = entry;
            }
        } else if (ctrl) {
            if (!win.multiSelection.add(entry)) {
                win.multiSelection.remove(entry);
            }
            win.selectionAnchor = entry;
            win.selectedPath = win.multiSelection.contains(entry)
                    ? entry
                    : (win.multiSelection.isEmpty() ? null : win.multiSelection.iterator().next());
        } else {
            win.multiSelection.clear();
            win.multiSelection.add(entry);
            win.selectionAnchor = entry;
            win.selectedPath = entry;
        }
        if (!isDir && win.selectedPath != null && win.selectedPath.equals(entry) && !shift && !ctrl) {
            onFileClicked(drive, safeRel(drive, entry), LiskovFs.get().fileTypes().detect(entry));
        }
    }

    private void stepSelection(ExplorerWindowState win, DriveMount drive, int delta, boolean shift) {
        List<Path> list = win.activeEntries;
        if (list == null || list.isEmpty()) return;
        int cur = win.selectedPath != null ? list.indexOf(win.selectedPath) : -1;
        int next = cur < 0 ? (delta > 0 ? 0 : list.size() - 1) : Math.max(0, Math.min(list.size() - 1, cur + delta));
        Path target = list.get(next);
        if (shift && win.selectionAnchor != null) {
            int anchor = list.indexOf(win.selectionAnchor);
            if (anchor >= 0) {
                win.multiSelection.clear();
                for (int i = Math.min(anchor, next); i <= Math.max(anchor, next); i++) {
                    win.multiSelection.add(list.get(i));
                }
                win.selectedPath = target;
                return;
            }
        }
        win.multiSelection.clear();
        win.multiSelection.add(target);
        win.selectionAnchor = target;
        win.selectedPath = target;
        if (!Files.isDirectory(target)) {
            onFileClicked(drive, safeRel(drive, target), LiskovFs.get().fileTypes().detect(target));
        }
    }

    private void enterDirectory(ExplorerWindowState win, Path dir) {
        if (!win.columnDirs.contains(dir)) {
            win.columnDirs.add(dir);
        }
        win.selectedPath = null;
        win.multiSelection.clear();
        win.selectionAnchor = null;
    }

    private void navigateUp(ExplorerWindowState win) {
        if (!win.columnDirs.isEmpty()) {
            Path removed = win.columnDirs.remove(win.columnDirs.size() - 1);
            win.selectedPath = removed;
            win.multiSelection.clear();
            win.multiSelection.add(removed);
            win.selectionAnchor = removed;
        }
    }

    private void closeWindow(int windowId) {
        if (windowId == 1 && windows.size() == 1) {
            setOpen(false);
            return;
        }
        windows.removeIf(w -> w.windowId == windowId);
        if (windows.isEmpty()) {
            setOpen(false);
        } else {
            activeWindowId = windows.get(0).windowId;
        }
    }

    private void copySelection(ExplorerWindowState win, DriveMount drive, boolean cut) {
        List<Path> sel = new ArrayList<>(win.multiSelection);
        if (sel.isEmpty() && win.selectedPath != null) sel.add(win.selectedPath);
        if (sel.isEmpty()) return;
        clipDriveId = drive.driveId();
        clipRelPaths.clear();
        for (Path p : sel) {
            String rel = safeRel(drive, p);
            if (!rel.isEmpty()) clipRelPaths.add(rel);
        }
        clipCut = cut;
        MubWidgets.ToastHost.push((cut ? "Cut " : "Copied ") + clipRelPaths.size() + " item(s)", SemanticColor.PLAYER);
    }

    private void pasteClipboard(ExplorerWindowState win, DriveMount dstDrive) {
        Path activeRoot = resolveActiveRoot(LiskovFs.get(), dstDrive, win);
        Path targetDir = currentDirectory(win, activeRoot);
        pasteIntoDir(dstDrive, targetDir);
    }

    private void pasteIntoDir(DriveMount dstDrive, Path targetDir) {
        if (clipRelPaths.isEmpty() || clipDriveId == null || dstDrive.isOffline()) return;
        DriveMount srcDrive = LiskovFs.get().drives().get(clipDriveId);
        if (srcDrive == null) return;
        String dstFolderRel = safeRel(dstDrive, targetDir);
        int ok = 0;
        for (String srcRel : List.copyOf(clipRelPaths)) {
            try {
                if (clipCut && srcDrive.driveId().equals(dstDrive.driveId())) {
                    LiskovFs.get().drives().transfer(srcDrive, srcRel, dstDrive, dstFolderRel);
                } else {
                    Path src = LiskovPaths.resolveInside(srcDrive.rootDir(), srcRel);
                    Path dstDir = dstFolderRel.isEmpty() ? dstDrive.rootDir() : LiskovPaths.resolveInside(dstDrive.rootDir(), dstFolderRel);
                    Files.createDirectories(dstDir);
                    String unique = LiskovPaths.uniqueName(src.getFileName().toString(), n -> Files.exists(dstDir.resolve(n)));
                    Path dst = dstDir.resolve(unique);
                    if (Files.isDirectory(src)) LiskovPaths.copyDirectoryOverwrite(src, dst);
                    else Files.copy(src, dst);
                }
                ok++;
            } catch (Exception ignored) {}
        }
        if (clipCut) {
            clipRelPaths.clear();
            clipCut = false;
        }
        MubWidgets.ToastHost.push("Pasted " + ok + " item(s)", SemanticColor.MR);
    }

    private void trashSelection(ExplorerWindowState win, LiskovFs fs) {
        List<Path> sel = new ArrayList<>(win.multiSelection);
        if (sel.isEmpty() && win.selectedPath != null) sel.add(win.selectedPath);
        if (sel.isEmpty()) return;
        int ok = 0;
        for (Path p : sel) {
            try {
                trash.moveToTrash(p, fs.fileTypes().detect(p).id());
                ok++;
            } catch (Exception ignored) {}
        }
        win.multiSelection.clear();
        win.selectedPath = null;
        win.selectionAnchor = null;
        MubWidgets.ToastHost.push("Moved " + ok + " item(s) to .trash/", SemanticColor.DANGER);
    }

    private void onFileClicked(DriveMount drive, String rel, LiskovFileType ft) {
        favorites.recordOpened(drive.driveId(), rel, Path.of(rel).getFileName().toString(), ft.id());
        try { favorites.save(LiskovFs.get().favoritesFile()); } catch (Exception ignored) {}

        if (!drive.isLocal() && !drive.isOffline() && drive.cacheStateOf(rel) == CacheState.CLOUD) {
            String repoId = VcsStore.repoIdFor(rel);
            for (SyncWire.FolderSummary fsSum : LiskovSyncClient.get().foldersOf(drive.driveId())) {
                for (SyncWire.RepoEntry e : fsSum.entries) {
                    if (repoId.equals(e.repoId)) {
                        LiskovSyncClient.get().requestDownload(e);
                        MubWidgets.ToastHost.push("Downloading " + e.relativePath + "...", SemanticColor.PLAYER);
                        return;
                    }
                }
            }
        }
    }

    private void acceptDropTarget(DriveMount dstDrive, String dstFolderRel) {
        if (ImGui.beginDragDropTarget()) {
            Object payload = ImGui.acceptDragDropPayload(DND_PAYLOAD);
            if (payload != null && pendingDrag != null) {
                DragPayload dp = pendingDrag;
                pendingDrag = null;
                DriveMount srcDrive = LiskovFs.get().drives().get(dp.driveId());
                if (srcDrive != null) {
                    int ok = 0;
                    LiskovDrives.TransferResult lastRes = null;
                    for (String rel : dp.relativePaths()) {
                        try {
                            lastRes = LiskovFs.get().drives().transfer(srcDrive, rel, dstDrive, dstFolderRel);
                            ok++;
                        } catch (Exception e) {
                            MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.WARN);
                        }
                    }
                    if (ok == 1 && lastRes != null) {
                        MubWidgets.ToastHost.push(
                                (lastRes.kind() == LiskovDrives.TransferKind.COPIED ? "COPIED " : "MOVED ")
                                        + lastRes.destination().getFileName(),
                                lastRes.kind() == LiskovDrives.TransferKind.COPIED ? SemanticColor.PLAYER : SemanticColor.MR);
                    } else if (ok > 1 && lastRes != null) {
                        MubWidgets.ToastHost.push(
                                (lastRes.kind() == LiskovDrives.TransferKind.COPIED ? "COPIED " : "MOVED ") + ok + " items",
                                lastRes.kind() == LiskovDrives.TransferKind.COPIED ? SemanticColor.PLAYER : SemanticColor.MR);
                    }
                }
            }
            ImGui.endDragDropTarget();
        }
    }

    private static Path resolveActiveRoot(LiskovFs fs, DriveMount drive, ExplorerWindowState win) {
        if (drive.isLocal()) {
            LiskovNamespace ns = fs.namespace(win.namespaceId);
            return ns != null ? ns.rootDir() : fs.schematicsDir();
        }
        return drive.rootDir();
    }

    private static Path currentDirectory(ExplorerWindowState win, Path activeRoot) {
        for (int i = win.columnDirs.size() - 1; i >= 0; i--) {
            Path p = win.columnDirs.get(i);
            if (Files.isDirectory(p) && p.startsWith(activeRoot)) return p;
        }
        return activeRoot;
    }

    private static List<Path> listSorted(Path dir, String query, String typeFilter, LiskovFs fs) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith(".") || name.equals("_folders.json") || name.equals("drive.json")) continue;
                boolean isDir = Files.isDirectory(p);
                if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query)) continue;
                if (!isDir && typeFilter != null && !fs.fileTypes().detect(p).id().equals(typeFilter)) continue;
                out.add(p);
            }
        } catch (IOException ignored) {
        }
        out.sort(Comparator.<Path, Boolean>comparing(p -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String safeRel(DriveMount drive, Path p) {
        try {
            return drive.rootDir().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (Exception e) {
            return "";
        }
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); } catch (Exception e) { return 0L; }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String formatDate(Path p) {
        try {
            return LIST_DATE_FMT.format(Files.getLastModifiedTime(p).toInstant());
        } catch (Exception e) {
            return "--";
        }
    }

    private static String clipText(String s, float maxWidth) {
        if (s == null || s.isEmpty()) return "";
        if (ImGui.calcTextSize(s).x <= maxWidth) return s;
        for (int len = s.length() - 1; len > 1; len--) {
            String cand = s.substring(0, len) + "...";
            if (ImGui.calcTextSize(cand).x <= maxWidth) return cand;
        }
        return "...";
    }
}
