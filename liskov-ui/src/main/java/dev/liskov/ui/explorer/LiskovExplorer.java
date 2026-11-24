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
import dev.liskov.core.vcs.VcsHead;
import dev.liskov.core.vcs.VcsStore;
import dev.liskov.net.LiskovReloadBus;
import dev.liskov.net.LiskovSyncClient;
import dev.liskov.ui.host.LiskovUiHost;
import dev.liskov.ui.host.UiLayer;
import dev.liskov.ui.menu.MenuOverviewRegistry;
import dev.liskov.ui.theme.MubTheme;
import dev.liskov.ui.theme.MubWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiDragDropFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Multi-window Miller-column Liskov File Explorer ({@code UiLayer} + embeddable workspace panel).
 * Supports {@code LOCAL} + named persistent {@code Server Drives}, cross-window drag-and-drop,
 * Smart On-Demand {@code CLOUD -> CACHED} downloads, Favorites, {@code .trash/}, and {@link InspectorPane}.
 */
public final class LiskovExplorer implements UiLayer {
    private static final String DND_PAYLOAD = "LISKOV_ITEM";
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

    private record DragPayload(String driveId, String relativePath) {}
    private DragPayload pendingDrag;

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
                MubTheme.textColored(MubTheme.DIM, "1 LOCAL · " + srvCount + " Server Drive(s)");
                if (!favorites.favorites.isEmpty()) {
                    MubWidgets.sectionHeader("FAVORITES");
                    for (int i = 0; i < Math.min(3, favorites.favorites.size()); i++) {
                        var fav = favorites.favorites.get(i);
                        if (MubWidgets.termButton("★ " + fav.label + "##ovf_" + i, SemanticColor.INK_MUTED)) {
                            jumpTo(fav.driveId, fav.relativePath);
                            setOpen(true);
                        }
                    }
                }
                if (MubWidgets.termButton("OPEN LISKOV (F8)", SemanticColor.FOCUS)) {
                    setOpen(true);
                }
                ImGui.sameLine();
                if (MubWidgets.termButton("COPY ROOT", SemanticColor.INK_MUTED)) {
                    lfs.copyPathToClipboard(lfs.liskovRoot());
                    MubWidgets.ToastHost.push("Copied Liskov root path", SemanticColor.PLAYER);
                }
            }
        });
    }

    public boolean isOpen() { return open; }

    public void setOpen(boolean open) {
        this.open = open;
        Minecraft mc = Minecraft.getInstance();
        if (open && mc != null && mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }
    }

    public void toggle() { setOpen(!open); }

    public void spawnSecondWindow() {
        if (windows.size() >= 4) return;
        ExplorerWindowState w = new ExplorerWindowState(nextWindowId++);
        var servers = LiskovFs.get().drives().serverDrives();
        if (!servers.isEmpty()) {
            w.driveId = servers.get(0).driveId();
        }
        windows.add(w);
        activeWindowId = w.windowId;
    }

    public void jumpTo(String driveId, String relPath) {
        ExplorerWindowState w = windows.get(0);
        w.driveId = driveId;
        w.viewingTrash = false;
        DriveMount d = LiskovFs.get().drives().get(driveId);
        if (d != null) {
            Path p = LiskovPaths.resolveInside(d.rootDir(), relPath);
            w.selectedPath = p;
        }
    }

    @Override public String id() { return "liskov.explorer"; }
    @Override public int order() { return 100; }

    @Override
    public boolean visible() {
        return open || FilePickerModal.isOpen() || MubWidgets.CommandPalette.isOpen();
    }

    @Override
    public boolean wantsKey(int key, int scancode, int action, int mods) {
        if (action == dev.liskov.ui.host.InputMap.GLFW_PRESS) {
            boolean ctrl = (mods & (dev.liskov.ui.host.InputMap.GLFW_MOD_CONTROL | dev.liskov.ui.host.InputMap.GLFW_MOD_SUPER)) != 0;
            if (ctrl && key == 80 /* GLFW_KEY_P */) return true;
            if (key == 297 /* GLFW_KEY_F8 */ || key == 256 /* GLFW_KEY_ESCAPE */) return open || FilePickerModal.isOpen() || MubWidgets.CommandPalette.isOpen();
        }
        return LiskovUiHost.imguiWantsKeyboard();
    }

    @Override
    public void onKey(int key, int scancode, int action, int mods) {
        if (action != dev.liskov.ui.host.InputMap.GLFW_PRESS) return;
        boolean ctrl = (mods & (dev.liskov.ui.host.InputMap.GLFW_MOD_CONTROL | dev.liskov.ui.host.InputMap.GLFW_MOD_SUPER)) != 0;
        if (ctrl && key == 80 /* GLFW_KEY_P */) {
            MubWidgets.CommandPalette.toggle();
            return;
        }
        if (key == 297 /* GLFW_KEY_F8 */ && !LiskovUiHost.imguiWantsTextInput()) {
            toggle();
            return;
        }
        if (key == 256 /* GLFW_KEY_ESCAPE */ && !LiskovUiHost.imguiWantsTextInput()) {
            if (MubWidgets.CommandPalette.isOpen()) MubWidgets.CommandPalette.close();
            else if (FilePickerModal.isOpen()) FilePickerModal.close();
            else if (open) setOpen(false);
        }
    }

    @Override
    public void draw() {
        if (open) {
            for (int i = 0; i < windows.size(); i++) {
                ExplorerWindowState win = windows.get(i);
                ImBoolean pOpen = new ImBoolean(true);
                var vp = ImGui.getMainViewport();
                float w = Math.min(1080f, vp.getSizeX() - 48f);
                float h = Math.min(640f, vp.getSizeY() - 64f);
                ImGui.setNextWindowPos(vp.getPosX() + 24f + i * 32f, vp.getPosY() + 32f + i * 24f, ImGuiCond.FirstUseEver);
                ImGui.setNextWindowSize(w, h, ImGuiCond.FirstUseEver);

                if (ImGui.begin("Liskov Explorer #" + win.windowId + "###LiskovWin_" + win.windowId, pOpen, ImGuiWindowFlags.NoCollapse)) {
                    if (ImGui.isWindowFocused(imgui.flag.ImGuiFocusedFlags.RootAndChildWindows)) {
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

        // Top Bar
        MubTheme.textColored(MubTheme.colorFor(SemanticColor.FOCUS), "■ " + drive.displayName());
        ImGui.sameLine();
        MubTheme.textColored(MubTheme.DIM, (win.selectedPath != null ? win.selectedPath : activeRoot).toString());
        ImGui.sameLine();
        if (MubWidgets.termButton("COPY PATH##cp_" + win.windowId, SemanticColor.INK_MUTED)) {
            fs.copyPathToClipboard(win.selectedPath != null ? win.selectedPath : activeRoot);
            MubWidgets.ToastHost.push("Copied path", SemanticColor.PLAYER);
        }
        ImGui.sameLine();
        if (MubWidgets.termButton("EXPLORER##ex_" + win.windowId, SemanticColor.INK_MUTED)) {
            fs.openInExplorer(win.selectedPath != null ? win.selectedPath : activeRoot);
        }
        ImGui.sameLine();
        if (watcher != null && MubWidgets.termButton("↻ RELOAD##rl_" + win.windowId, SemanticColor.INK_MUTED)) {
            watcher.reloadNow();
            MubWidgets.ToastHost.push("ReloadWatcher scan complete", SemanticColor.MR);
        }
        if (watcher != null) {
            ImGui.sameLine();
            if (MubWidgets.termToggle("aw_" + win.windowId, "WATCH", watcher.isEnabled(), SemanticColor.MR)) {
                watcher.setEnabled(!watcher.isEnabled());
            }
        }
        ImGui.sameLine();
        if (MubWidgets.termButton("+ NEW WINDOW##nw_" + win.windowId, SemanticColor.FOCUS)) {
            spawnSecondWindow();
        }
        ImGui.sameLine();
        if (MubWidgets.termButton("THEME: " + MubTheme.preset().displayName + "##th_" + win.windowId, SemanticColor.META)) {
            MubTheme.cyclePreset();
        }

        // Offline server banner
        if (drive.isOffline()) {
            MubWidgets.termBadge("OFFLINE · READ-ONLY", SemanticColor.INK_MUTED);
            ImGui.sameLine();
            MubTheme.textColored(MubTheme.DIM, "Server ID " + drive.driveId() + " · Browse cached (✓) or drag to LOCAL.");
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

        // Filter & View bar
        ImGui.setNextItemWidth(170f);
        ImGui.inputTextWithHint("##search_" + win.windowId, "Filter files...", win.search);
        for (LiskovFileType ft : fs.fileTypes().all()) {
            if (ft.primaryExtension().isEmpty() || ft == dev.liskov.core.FileTypeRegistry.CONFIG || ft == dev.liskov.core.FileTypeRegistry.LOG) continue;
            ImGui.sameLine();
            boolean active = ft.id().equals(win.filterFileTypeId);
            if (MubWidgets.termButton(ft.monogram() + "##ft_" + win.windowId + "_" + ft.id(),
                    active ? ft.colorRole() : SemanticColor.INK_MUTED)) {
                win.filterFileTypeId = active ? null : ft.id();
            }
        }
        ImGui.sameLine();
        if (MubWidgets.termButton("COLS##v1_" + win.windowId,
                win.viewMode == ExplorerWindowState.ViewMode.COLUMNS ? SemanticColor.FOCUS : SemanticColor.INK_MUTED)) {
            win.viewMode = ExplorerWindowState.ViewMode.COLUMNS;
        }
        ImGui.sameLine();
        if (MubWidgets.termButton("LIST##v2_" + win.windowId,
                win.viewMode == ExplorerWindowState.ViewMode.LIST ? SemanticColor.FOCUS : SemanticColor.INK_MUTED)) {
            win.viewMode = ExplorerWindowState.ViewMode.LIST;
        }
        ImGui.sameLine();
        if (MubWidgets.termButton("GRID##v3_" + win.windowId,
                win.viewMode == ExplorerWindowState.ViewMode.GRID ? SemanticColor.FOCUS : SemanticColor.INK_MUTED)) {
            win.viewMode = ExplorerWindowState.ViewMode.GRID;
        }

        ImGui.separator();
        float bodyH = ImGui.getContentRegionAvailY();

        // 1. Left Drives Sidebar
        if (ImGui.beginChild("##sidebar_" + win.windowId, 185f, bodyH, true)) {
            drawDrivesSidebar(win, fs);
        }
        ImGui.endChild();
        ImGui.sameLine();

        // 2. Center Miller Columns / List / Grid
        float inspW = (win.windowId == activeWindowId || windows.size() == 1) ? 350f : 0f;
        float centerW = Math.max(220f, ImGui.getContentRegionAvailX() - inspW - (inspW > 0 ? 8f : 0f));
        if (ImGui.beginChild("##center_" + win.windowId, centerW, bodyH, true, ImGuiWindowFlags.HorizontalScrollbar)) {
            if (win.viewingTrash) {
                drawTrashView(win);
            } else if (win.viewMode == ExplorerWindowState.ViewMode.COLUMNS) {
                drawMillerColumns(win, fs, drive, activeRoot, bodyH - 16f);
            } else {
                Path curDir = win.columnDirs.isEmpty() ? activeRoot : win.columnDirs.get(win.columnDirs.size() - 1);
                drawDirectoryFlatList(win, fs, drive, curDir);
            }
        }
        ImGui.endChild();

        // 3. Right Inspector
        if (inspW > 0f) {
            ImGui.sameLine();
            if (ImGui.beginChild("##insp_" + win.windowId, inspW, bodyH, true)) {
                inspector.draw(win, drive, vcs, favorites, trash, watcher);
            }
            ImGui.endChild();
        }
    }

    private void drawDrivesSidebar(ExplorerWindowState win, LiskovFs fs) {
        MubWidgets.sectionHeader("FAVORITES");
        for (int i = 0; i < favorites.favorites.size(); i++) {
            var fav = favorites.favorites.get(i);
            if (ImGui.selectable("★ " + fav.label + "##favs_" + win.windowId + "_" + i, false)) {
                jumpTo(fav.driveId, fav.relativePath);
            }
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
            }
            acceptDropTarget(fs.drives().local(), ns.id());
        }
        if (ImGui.selectable(".trash##tr_" + win.windowId, win.viewingTrash)) {
            win.driveId = LiskovDrives.LOCAL_DRIVE_ID;
            win.viewingTrash = true;
            win.selectedPath = null;
        }
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
            }
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
            if (ImGui.beginChild("##col_" + win.windowId + "_" + c, 210f, colH, true)) {
                String title = c == 0 ? (drive.isLocal() ? win.namespaceId.toUpperCase(Locale.ROOT) : drive.displayName())
                        : dir.getFileName().toString();
                MubTheme.textColored(MubTheme.DIM, title);
                ImGui.separator();

                List<Path> entries = listSorted(dir, win.search.get().trim().toLowerCase(Locale.ROOT), win.filterFileTypeId, fs);
                for (Path entry : entries) {
                    boolean isDir = Files.isDirectory(entry);
                    String name = entry.getFileName().toString();
                    boolean sel = entry.equals(win.selectedPath) || (c < win.columnDirs.size() && entry.equals(win.columnDirs.get(c)));
                    if (isDir) {
                        if (ImGui.selectable("▣ " + name + " ▸##d_" + c + "_" + name, sel)) {
                            while (win.columnDirs.size() > c) win.columnDirs.remove(win.columnDirs.size() - 1);
                            win.columnDirs.add(entry);
                            win.selectedPath = null;
                        }
                        String relDir = safeRel(drive, entry);
                        acceptDropTarget(drive, relDir);
                    } else {
                        LiskovFileType ft = fs.fileTypes().detect(entry);
                        MubWidgets.fileTypeBadge(ft, false);
                        ImGui.sameLine();
                        String rel = safeRel(drive, entry);
                        VcsHead head = vcs.loadHead(drive.driveId(), VcsStore.repoIdFor(rel));
                        String suffix = head.latestVersion > 0 ? " [v" + head.latestVersion + "]" : "";
                        if (ImGui.selectable(name + suffix + "##f_" + c + "_" + name, sel)) {
                            while (win.columnDirs.size() > c) win.columnDirs.remove(win.columnDirs.size() - 1);
                            win.selectedPath = entry;
                            onFileClicked(drive, rel, ft);
                        }
                        if (ImGui.beginDragDropSource(ImGuiDragDropFlags.None)) {
                            pendingDrag = new DragPayload(drive.driveId(), rel);
                            ImGui.setDragDropPayload(DND_PAYLOAD, rel);
                            ImGui.textUnformatted(name);
                            ImGui.endDragDropSource();
                        }
                    }
                }
                if (!drive.isOffline()) {
                    ImGui.separator();
                    ImGui.setNextItemWidth(135f);
                    ImGui.inputTextWithHint("##nf_" + win.windowId + "_" + c, "Folder...", newFolderName);
                    ImGui.sameLine();
                    if (MubWidgets.termButton("+##nfb_" + win.windowId + "_" + c, SemanticColor.INK_MUTED) && !newFolderName.get().isBlank()) {
                        try {
                            Files.createDirectories(dir.resolve(LiskovPaths.safeName(newFolderName.get())));
                            newFolderName.set("");
                        } catch (IOException ignored) {}
                    }
                }
            }
            ImGui.endChild();
            acceptDropTarget(drive, safeRel(drive, dir));
        }
    }

    private void drawDirectoryFlatList(ExplorerWindowState win, LiskovFs fs, DriveMount drive, Path dir) {
        List<Path> entries = listSorted(dir, win.search.get().trim().toLowerCase(Locale.ROOT), win.filterFileTypeId, fs);
        for (Path entry : entries) {
            boolean isDir = Files.isDirectory(entry);
            String name = entry.getFileName().toString();
            if (isDir) {
                if (ImGui.selectable("▣ " + name + "##flat_" + name, entry.equals(win.selectedPath))) {
                    win.columnDirs.add(entry);
                }
            } else {
                LiskovFileType ft = fs.fileTypes().detect(entry);
                MubWidgets.fileTypeBadge(ft, false);
                ImGui.sameLine();
                String rel = safeRel(drive, entry);
                if (ImGui.selectable(name + "##flat_" + name, entry.equals(win.selectedPath))) {
                    win.selectedPath = entry;
                    onFileClicked(drive, rel, ft);
                }
                if (ImGui.beginDragDropSource(ImGuiDragDropFlags.None)) {
                    pendingDrag = new DragPayload(drive.driveId(), rel);
                    ImGui.setDragDropPayload(DND_PAYLOAD, rel);
                    ImGui.textUnformatted(name);
                    ImGui.endDragDropSource();
                }
            }
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
        }
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
                try {
                    var res = LiskovFs.get().drives().transfer(srcDrive, dp.relativePath(), dstDrive, dstFolderRel);
                    MubWidgets.ToastHost.push(
                            (res.kind() == LiskovDrives.TransferKind.COPIED ? "COPIED " : "MOVED ")
                                    + res.destination().getFileName(),
                            res.kind() == LiskovDrives.TransferKind.COPIED ? SemanticColor.PLAYER : SemanticColor.MR);
                } catch (Exception e) {
                    MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.WARN);
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
}
