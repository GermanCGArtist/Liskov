package dev.liskov.ui.explorer;

import dev.liskov.core.CacheState;
import dev.liskov.core.CompletionTag;
import dev.liskov.core.DriveMount;
import dev.liskov.core.FavoritesStore;
import dev.liskov.core.LiskovFileType;
import dev.liskov.core.LiskovFs;
import dev.liskov.core.LiskovPaths;
import dev.liskov.core.LitematicReader;
import dev.liskov.core.ReloadWatcher;
import dev.liskov.core.SemanticColor;
import dev.liskov.core.TrashManager;
import dev.liskov.core.acl.AclRole;
import dev.liskov.core.sync.SyncWire;
import dev.liskov.core.vcs.VcsCommit;
import dev.liskov.core.vcs.VcsHead;
import dev.liskov.core.vcs.VcsMergeResult;
import dev.liskov.core.vcs.VcsStore;
import dev.liskov.net.LiskovReloadBus;
import dev.liskov.net.LiskovSyncClient;
import dev.liskov.ui.preview.SchematicPreview3D;
import dev.liskov.ui.theme.MubTheme;
import dev.liskov.ui.theme.MubWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Right-hand Inspector column for {@link LiskovExplorer}:
 * {@code [INSPECT]} (3D preview + Y-layer slice / inline quick-edit + cross-mod actions),
 * {@code [HISTORY]} (hidden {@code .liskov-vcs/} timeline + {@code BRANCHES} + 3D preview of old saves + Restore),
 * {@code [SHARING & ACL]}, plus bulk multi-selection actions when multiple files/folders are selected.
 */
final class InspectorPane {
    private enum Tab { INSPECT, HISTORY, SHARING }

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

    private Tab activeTab = Tab.INSPECT;
    private boolean showBranches = false;
    private boolean editingText = false;
    private Path editingPath;
    private final ImString editorBuf = new ImString(64 * 1024);
    private final ImString branchBuf = new ImString(64);
    private final ImString snapshotNoteBuf = new ImString(128);
    private final ImString lockConfirmBuf = new ImString(32);
    private final ImString aclSubjectBuf = new ImString(64);
    private String previewCommitId;
    private VcsCommit previewCommit;
    private boolean showUnlockPopup = false;
    private String unlockTargetRepoId = "";
    private String unlockTargetFolderId = "shared";

    private final SchematicPreview3D preview3d = new SchematicPreview3D();

    void draw(
            ExplorerWindowState win,
            DriveMount drive,
            VcsStore vcs,
            FavoritesStore favs,
            TrashManager trash,
            ReloadWatcher watcher
    ) {
        if (win.multiSelection.size() > 1) {
            drawMultiSelectionSummary(win, drive, trash);
            return;
        }
        if (win.selectedPath == null || !Files.exists(win.selectedPath) || Files.isDirectory(win.selectedPath)) {
            MubTheme.textColored(MubTheme.GUTTER, "Select a file to inspect its 3D preview, history, and sharing.");
            if (win.selectedPath != null && Files.isDirectory(win.selectedPath)) {
                ImGui.spacing();
                MubWidgets.sectionHeader("FOLDER");
                MubTheme.textColored(MubTheme.FG, win.selectedPath.getFileName().toString());
                MubTheme.textColored(MubTheme.DIM, "Double-click or press Enter to open.");
            }
            return;
        }
        Path file = win.selectedPath;
        LiskovFs fs = LiskovFs.get();
        LiskovFileType ft = fs.fileTypes().detect(file);
        String relPath = safeRel(drive, file);
        String repoId = VcsStore.repoIdFor(relPath);
        boolean offline = drive.isOffline();
        CacheState cache = drive.cacheStateOf(relPath);

        if (cache == CacheState.CLOUD && offline) {
            MubWidgets.fileTypeBadge(ft, true);
            ImGui.sameLine();
            MubTheme.textColored(MubTheme.FG, file.getFileName().toString());
            MubWidgets.termBadge("CLOUD ONLY (OFFLINE)", SemanticColor.PLAYER);
            ImGui.spacing();
            MubTheme.textColored(MubTheme.DIM, "Not cached locally. Connect to '" + drive.displayName() + "' to download on demand.");
            return;
        }

        // Header
        MubWidgets.fileTypeBadge(ft, true);
        ImGui.sameLine();
        ImGui.beginGroup();
        MubTheme.textColored(MubTheme.FG, file.getFileName().toString());
        MubWidgets.tooltip(file.toAbsolutePath().normalize().toString());
        VcsHead head = vcs.loadHead(drive.driveId(), repoId);
        MubWidgets.termBadge("v" + Math.max(1, head.latestVersion), SemanticColor.FOCUS);
        MubWidgets.tooltip("Version", "Latest local .liskov-vcs/ snapshot version.");
        ImGui.sameLine();
        if (drive.isLocal()) {
            MubWidgets.termBadge("LOCAL", SemanticColor.PLAYER);
            MubWidgets.tooltip("Drive: LOCAL", "Private workspace stored on your computer.");
        } else {
            MubWidgets.termBadge(cache == CacheState.PINNED_OFFLINE ? "KEPT OFFLINE" : "CACHED",
                    cache == CacheState.PINNED_OFFLINE ? SemanticColor.MR : SemanticColor.BOT);
            MubWidgets.tooltip("Cache Status", cache == CacheState.PINNED_OFFLINE
                    ? "Pinned for offline browsing."
                    : "Downloaded on demand from server drive.");
        }
        CompletionTag tag = FileActionRegistry.statusOf(file, ft);
        if (tag != CompletionTag.NOT_STARTED) {
            ImGui.sameLine();
            MubWidgets.termBadge(tag.label(), tag.colorRole());
        }
        ImGui.endGroup();

        ImGui.sameLine();
        boolean isFav = favs.isFavorite(drive.driveId(), relPath);
        if (MubWidgets.starButton("fav", isFav, SemanticColor.WARN)) {
            favs.toggleFavorite(drive.driveId(), relPath, file.getFileName().toString(), ft.id(), false);
            try { favs.save(fs.favoritesFile()); } catch (Exception ignored) {}
        }
        MubWidgets.tooltip(isFav ? "Remove Favorite" : "Pin to Favorites",
                "Pin or unpin this item in the left Favorites sidebar.");

        ImGui.separator();
        // 3 Inspector Tabs
        float tabW = Math.max(70f, (ImGui.getContentRegionAvailX() - 12f) / 3f);
        if (MubWidgets.termButton("INSPECT", activeTab == Tab.INSPECT ? SemanticColor.FOCUS : SemanticColor.INK_MUTED, tabW, 24f)) {
            activeTab = Tab.INSPECT;
        }
        MubWidgets.tooltip("Inspect Tab", "3D preview, text editor, materials, and file actions.");
        ImGui.sameLine();
        List<VcsCommit> commits = vcs.loadCommits(drive.driveId(), repoId);
        if (MubWidgets.termButton("HISTORY (" + commits.size() + ")", activeTab == Tab.HISTORY ? SemanticColor.FOCUS : SemanticColor.INK_MUTED, tabW, 24f)) {
            activeTab = Tab.HISTORY;
        }
        MubWidgets.tooltip("History Tab", "View .liskov-vcs/ save snapshots, branches, and restore older versions.");
        ImGui.sameLine();
        if (MubWidgets.termButton("SHARING", activeTab == Tab.SHARING ? SemanticColor.BOT : SemanticColor.INK_MUTED, tabW, 24f)) {
            activeTab = Tab.SHARING;
        }
        MubWidgets.tooltip("Sharing & ACL Tab", "Server drive sync status, file locks, and cross-drive copy.");
        ImGui.separator();

        switch (activeTab) {
            case INSPECT -> drawInspectTab(win, drive, file, relPath, repoId, ft, vcs, trash, watcher, offline, cache);
            case HISTORY -> drawHistoryTab(drive, file, relPath, repoId, ft, vcs, head, commits, offline);
            case SHARING -> drawSharingTab(drive, file, relPath, repoId, offline);
        }

        drawUnlockModal();
    }

    private void drawMultiSelectionSummary(ExplorerWindowState win, DriveMount drive, TrashManager trash) {
        int count = win.multiSelection.size();
        MubWidgets.termBadge(count + " ITEMS SELECTED", SemanticColor.FOCUS);
        ImGui.separator();

        long totalBytes = 0L;
        int files = 0, dirs = 0;
        for (Path p : win.multiSelection) {
            if (Files.isDirectory(p)) {
                dirs++;
            } else if (Files.exists(p)) {
                files++;
                try { totalBytes += Files.size(p); } catch (Exception ignored) {}
            }
        }
        MubTheme.textColored(MubTheme.DIM, files + " file(s), " + dirs + " folder(s) | " + LiskovExplorer.formatSize(totalBytes));
        ImGui.spacing();

        int shown = 0;
        for (Path p : win.multiSelection) {
            if (shown++ >= 10) {
                MubTheme.textColored(MubTheme.GUTTER, "... and " + (count - 10) + " more");
                break;
            }
            MubTheme.textColored(MubTheme.FG, "  - " + p.getFileName());
        }

        ImGui.separator();
        MubWidgets.sectionHeader("BULK ACTIONS");
        boolean offline = drive.isOffline();

        if (drive.isLocal()) {
            DriveMount onlineSrv = activeOnlineServer();
            if (onlineSrv != null && MubWidgets.termButton("COPY ALL -> " + onlineSrv.displayName(), SemanticColor.BOT)) {
                int ok = 0;
                for (Path p : List.copyOf(win.multiSelection)) {
                    try {
                        var res = LiskovFs.get().drives().transfer(drive, safeRel(drive, p), onlineSrv, "");
                        String dstRel = safeRel(onlineSrv, res.destination());
                        String ftId = LiskovFs.get().fileTypes().detect(res.destination()).id();
                        LiskovSyncClient.get().publishPath(null, res.destination(), dstRel, ftId,
                                playerUuid(), playerName(), "Uploaded selection", false);
                        ok++;
                    } catch (Exception ignored) {}
                }
                MubWidgets.ToastHost.push("Uploaded " + ok + " item(s) to " + onlineSrv.displayName(), SemanticColor.BOT);
            }
            MubWidgets.tooltip("Copy Selection to Server Drive", "Uploads copies of all selected items to the connected server drive.");
        } else {
            if (MubWidgets.termButton("COPY ALL -> LOCAL", SemanticColor.PLAYER)) {
                int ok = 0;
                for (Path p : List.copyOf(win.multiSelection)) {
                    try {
                        LiskovFs.get().drives().transfer(drive, safeRel(drive, p), LiskovFs.get().drives().local(), "schematics");
                        ok++;
                    } catch (Exception ignored) {}
                }
                MubWidgets.ToastHost.push("Copied " + ok + " item(s) to LOCAL:/schematics", SemanticColor.PLAYER);
            }
            MubWidgets.tooltip("Copy Selection to LOCAL", "Downloads copies of all selected items into LOCAL:/schematics.");
        }

        if (MubWidgets.termButton("COPY ALL PATHS", SemanticColor.INK_MUTED)) {
            StringBuilder sb = new StringBuilder();
            for (Path p : win.multiSelection) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(p.toAbsolutePath().normalize());
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.keyboardHandler != null) mc.keyboardHandler.setClipboard(sb.toString());
            MubWidgets.ToastHost.push("Copied " + count + " path(s)", SemanticColor.PLAYER);
        }
        MubWidgets.tooltip("Copy All Paths", "Copy every selected full path (one per line) to clipboard.");

        if (!offline && !win.viewingTrash && MubWidgets.termButton("TRASH ALL (Del)", SemanticColor.DANGER)) {
            int ok = 0;
            LiskovFs fs = LiskovFs.get();
            for (Path p : List.copyOf(win.multiSelection)) {
                try {
                    String rel = safeRel(drive, p);
                    trash.moveToTrash(p, fs.fileTypes().detect(p).id());
                    if (!drive.isLocal() && !rel.isEmpty()) {
                        LiskovSyncClient.get().requestDelete(null, VcsStore.repoIdFor(rel));
                    }
                    ok++;
                } catch (Exception ignored) {}
            }
            win.multiSelection.clear();
            win.selectedPath = null;
            MubWidgets.ToastHost.push("Moved " + ok + " item(s) to .trash/", SemanticColor.DANGER);
        }
        MubWidgets.tooltip("Trash Selected Items", "Move all selected files/folders to .trash/ (recoverable).");

        if (MubWidgets.termButton("CLEAR SELECTION", SemanticColor.INK_MUTED)) {
            win.multiSelection.clear();
            win.selectedPath = null;
            win.selectionAnchor = null;
        }
    }

    private void drawInspectTab(
            ExplorerWindowState win,
            DriveMount drive,
            Path file,
            String relPath,
            String repoId,
            LiskovFileType ft,
            VcsStore vcs,
            TrashManager trash,
            ReloadWatcher watcher,
            boolean offline,
            CacheState cache
    ) {
        if (ft.previewKind() == LiskovFileType.PreviewKind.SCHEMATIC_3D && file.getFileName().toString().endsWith(".litematic")) {
            if (previewCommit != null && previewCommitId != null) {
                MubWidgets.termBadge("PREVIEWING v" + previewCommit.versionNum, SemanticColor.WARN);
                ImGui.sameLine();
                if (!offline && MubWidgets.termButton("RESTORE", SemanticColor.WARN)) {
                    doRestore(drive, file, relPath, repoId, vcs, previewCommitId);
                }
                MubWidgets.tooltip("Restore Snapshot", "Replace live file with this historical snapshot.");
                ImGui.sameLine();
                if (MubWidgets.termButton("LIVE", SemanticColor.INK_MUTED)) {
                    previewCommitId = null;
                    previewCommit = null;
                }
                MubWidgets.tooltip("Back to Live", "Stop previewing old snapshot and show live file on disk.");
            }
            try {
                if (previewCommit != null) {
                    Map<String, byte[]> oldFiles = vcs.readCommitFiles(drive.driveId(), repoId, previewCommit.commitId);
                    byte[] raw = oldFiles.values().stream().findFirst().orElse(null);
                    if (raw != null) {
                        preview3d.setSummary(file + "@" + previewCommit.commitId,
                                LitematicReader.read(raw, file.getFileName().toString()));
                    }
                } else {
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    preview3d.setSummary(file + "@" + mtime, LitematicReader.read(file));
                }
                preview3d.drawInspectorPreview(ImGui.getContentRegionAvailX());
                LitematicReader.Summary sum = preview3d.summary();
                if (sum != null && !sum.topMaterials.isEmpty()) {
                    MubWidgets.sectionHeader("TOP MATERIALS (" + sum.totalBlocks + " blocks)");
                    for (int i = 0; i < Math.min(4, sum.topMaterials.size()); i++) {
                        var mc = sum.topMaterials.get(i);
                        MubTheme.textColored(MubTheme.DIM, mc.blockId.replace("minecraft:", "") + " : " + mc.count);
                    }
                }
            } catch (Exception e) {
                MubTheme.textColored(MubTheme.ERR, "Preview error: " + e.getMessage());
            }
            ImGui.separator();
        } else if (ft.previewKind() == LiskovFileType.PreviewKind.TEXT_EDITABLE
                || ft.previewKind() == LiskovFileType.PreviewKind.TEXT_READONLY) {
            boolean editable = ft.previewKind() == LiskovFileType.PreviewKind.TEXT_EDITABLE && !offline;
            if (!file.equals(editingPath)) {
                editingPath = file;
                editingText = false;
                try {
                    String txt = Files.readString(file, StandardCharsets.UTF_8);
                    editorBuf.set(txt.length() > 60_000 ? txt.substring(0, 60_000) : txt);
                } catch (Exception e) {
                    editorBuf.set("// Unreadable text file: " + e.getMessage());
                }
            }
            MubWidgets.sectionHeader(editingText ? "INLINE EDITOR" : "PREVIEW");
            if (editable) {
                ImGui.sameLine();
                if (!editingText) {
                    if (MubWidgets.termButton("QUICK EDIT", SemanticColor.FOCUS)) editingText = true;
                    MubWidgets.tooltip("Quick Edit", "Edit this text/script file inline and auto-snapshot on save.");
                } else {
                    if (MubWidgets.termButton("SAVE", SemanticColor.MR)) {
                        try {
                            byte[] bytes = editorBuf.get().getBytes(StandardCharsets.UTF_8);
                            LiskovPaths.writeAtomicBytes(file, bytes);
                            snapshotFile(drive, relPath, repoId, ft.id(), "Inline edit saved", false, bytes, vcs);
                            watcher.reloadNow();
                            editingText = false;
                            MubWidgets.ToastHost.push("Saved & snapshotted " + file.getFileName(), SemanticColor.MR);
                        } catch (Exception ex) {
                            MubWidgets.ToastHost.push("Save failed: " + ex.getMessage(), SemanticColor.DANGER);
                        }
                    }
                    MubWidgets.tooltip("Save & Snapshot", "Write atomically to disk and record a new .liskov-vcs/ version.");
                    ImGui.sameLine();
                    if (MubWidgets.termButton("CANCEL", SemanticColor.INK_MUTED)) editingText = false;
                }
            }
            int flags = editingText ? ImGuiInputTextFlags.AllowTabInput : ImGuiInputTextFlags.ReadOnly;
            ImGui.inputTextMultiline("##inline_edit", editorBuf, -1f, 180f, flags);
            ImGui.separator();
        }

        // Actions
        MubWidgets.sectionHeader("ACTIONS");
        if (drive.isLocal()) {
            DriveMount onlineSrv = activeOnlineServer();
            if (onlineSrv != null && MubWidgets.termButton("COPY -> " + onlineSrv.displayName(), SemanticColor.BOT)) {
                try {
                    var res = LiskovFs.get().drives().transfer(drive, relPath, onlineSrv, "");
                    String dstRel = safeRel(onlineSrv, res.destination());
                    LiskovSyncClient.get().publishPath(null, res.destination(), dstRel, ft.id(),
                            playerUuid(), playerName(), "Uploaded from LOCAL", false);
                    MubWidgets.ToastHost.push("Uploaded to " + onlineSrv.displayName(), SemanticColor.BOT);
                } catch (Exception e) {
                    MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
                }
            }
            MubWidgets.tooltip("Copy to Server Drive", "Keep local copy intact and upload onto the active server drive.");
        } else {
            if (MubWidgets.termButton("COPY -> LOCAL", SemanticColor.PLAYER)) {
                try {
                    LiskovFs.get().drives().transfer(drive, relPath, LiskovFs.get().drives().local(), "schematics");
                    MubWidgets.ToastHost.push("Copied to LOCAL:/schematics", SemanticColor.PLAYER);
                } catch (Exception e) {
                    MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
                }
            }
            MubWidgets.tooltip("Copy to LOCAL", "Copy this server file into LOCAL:/schematics for offline use.");
            if (!offline) {
                ImGui.sameLine();
                boolean pinned = cache == CacheState.PINNED_OFFLINE;
                if (MubWidgets.termButton(pinned ? "KEPT OFFLINE [x]" : "KEEP OFFLINE",
                        pinned ? SemanticColor.MR : SemanticColor.INK_MUTED)) {
                    drive.setPinnedOffline(relPath, !pinned);
                    try { drive.saveMetadata(); } catch (Exception ignored) {}
                }
                MubWidgets.tooltip("Pin Offline", "Always keep a local cached copy when disconnected from this server.");
            }
        }

        if (!offline && MubWidgets.termButton("+ SNAPSHOT", SemanticColor.MR)) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                VcsCommit c = snapshotFile(drive, relPath, repoId, ft.id(), "Manual snapshot", false, bytes, vcs);
                MubWidgets.ToastHost.push("Saved v" + c.versionNum + " to .liskov-vcs/", SemanticColor.MR);
            } catch (Exception e) {
                MubWidgets.ToastHost.push("Snapshot failed: " + e.getMessage(), SemanticColor.DANGER);
            }
        }
        MubWidgets.tooltip("Create VCS Snapshot", "Save a restore point into hidden .liskov-vcs/ storage.");

        for (FileActionRegistry.Action act : FileActionRegistry.actionsFor(ft)) {
            if (MubWidgets.termButton(act.label(), act.tone())) {
                act.onClick().accept(file);
            }
        }

        if (MubWidgets.termButton("COPY LLM PATH", SemanticColor.INK_MUTED)) {
            LiskovFs.get().copyPathToClipboard(file);
            MubWidgets.ToastHost.push("Copied path to clipboard", SemanticColor.PLAYER);
        }
        MubWidgets.tooltip("Copy Absolute Path", "Copy full path to clipboard for AI agents or scripts.");
        ImGui.sameLine();
        if (MubWidgets.termButton("EXPLORER", SemanticColor.INK_MUTED)) {
            LiskovFs.get().openInExplorer(file);
        }
        MubWidgets.tooltip("Show in OS Explorer", "Reveal this file in your system file manager.");
        if (!offline && !win.viewingTrash && MubWidgets.termButton("TRASH (Del)", SemanticColor.DANGER)) {
            try {
                trash.moveToTrash(file, ft.id());
                if (!drive.isLocal()) {
                    LiskovSyncClient.get().requestDelete(
                            LiskovSyncClient.get().findFolderIdForRepo(drive.driveId(), repoId), repoId);
                }
                win.selectedPath = null;
                win.multiSelection.remove(file);
                MubWidgets.ToastHost.push("Moved to .trash/ (recoverable)", SemanticColor.DANGER);
            } catch (Exception e) {
                MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
            }
        }
        MubWidgets.tooltip("Move to .trash/", "Soft-delete into recoverable Liskov .trash/ (shortcut: Del).");
    }

    private void drawHistoryTab(
            DriveMount drive,
            Path file,
            String relPath,
            String repoId,
            LiskovFileType ft,
            VcsStore vcs,
            VcsHead head,
            List<VcsCommit> commits,
            boolean offline
    ) {
        if (!offline) {
            ImGui.setNextItemWidth(Math.max(110f, ImGui.getContentRegionAvailX() - 115f));
            ImGui.inputTextWithHint("##snap_msg", "Snapshot note...", snapshotNoteBuf);
            ImGui.sameLine();
            if (MubWidgets.termButton("+ SAVE", SemanticColor.MR)) {
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    VcsCommit c = snapshotFile(drive, relPath, repoId, ft.id(), snapshotNoteBuf.get(), false, bytes, vcs);
                    snapshotNoteBuf.set("");
                    MubWidgets.ToastHost.push("Saved v" + c.versionNum, SemanticColor.MR);
                } catch (Exception e) {
                    MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
                }
            }
            MubWidgets.tooltip("Save Snapshot", "Record a new version with this note in .liskov-vcs/.");
        }

        if (MubWidgets.termToggle("br_toggle", "BRANCHES", showBranches, SemanticColor.FOCUS)) {
            showBranches = !showBranches;
        }
        MubWidgets.tooltip("Show Branches", "Switch or merge optional version branches.");
        if (!offline) {
            ImGui.sameLine();
            if (MubWidgets.termButton("PRUNE >30D", SemanticColor.INK_MUTED)) {
                try {
                    int n = vcs.prune(drive.driveId(), repoId, 30, 10);
                    MubWidgets.ToastHost.push("Pruned " + n + " old autosave(s)", SemanticColor.MR);
                } catch (Exception ignored) {}
            }
            MubWidgets.tooltip("Prune Old Autosaves", "Delete non-milestone autosaves older than 30 days (keeps >= 10 latest).");
            if (!drive.isLocal()) {
                ImGui.sameLine();
                if (MubWidgets.termButton("SYNC SERVER", SemanticColor.BOT)) {
                    LiskovSyncClient.get().requestHistory(repoId);
                    MubWidgets.ToastHost.push("Requested server version history", SemanticColor.BOT);
                }
                MubWidgets.tooltip("Sync Remote History", "Fetch commit history for this file from the connected server.");
            }
        }

        if (showBranches) {
            ImGui.separator();
            MubWidgets.sectionHeader("BRANCHES (ON: " + head.activeBranch + ")");
            for (String br : head.branchTips.keySet()) {
                boolean active = br.equals(head.activeBranch);
                MubTheme.textColored(active ? MubTheme.PLAYER : MubTheme.DIM, (active ? "* " : "  ") + br);
                if (!offline && !active) {
                    ImGui.sameLine();
                    if (MubWidgets.termButton("SWITCH##sw_" + br, SemanticColor.INK_MUTED)) {
                        try {
                            Map<String, byte[]> files = vcs.switchBranch(drive.driveId(), repoId, br);
                            writeSingleOrBundle(file, files);
                        } catch (Exception e) {
                            MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
                        }
                    }
                    ImGui.sameLine();
                    if (MubWidgets.termButton("MERGE##mg_" + br, SemanticColor.BOT)) {
                        try {
                            VcsMergeResult res = vcs.mergeBranch(drive.driveId(), repoId, br, playerUuid(), playerName());
                            if (res.mergedCommit() != null) {
                                writeSingleOrBundle(file, vcs.readCommitFiles(drive.driveId(), repoId, res.mergedCommit().commitId));
                            }
                            MubWidgets.ToastHost.push("Merged " + br + " -> " + head.activeBranch, SemanticColor.BOT);
                        } catch (Exception e) {
                            MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
                        }
                    }
                }
            }
            if (!offline) {
                ImGui.setNextItemWidth(160f);
                ImGui.inputTextWithHint("##new_br", "new-branch", branchBuf);
                ImGui.sameLine();
                if (MubWidgets.termButton("+ BRANCH", SemanticColor.FOCUS) && !branchBuf.get().isBlank()) {
                    try {
                        vcs.createBranch(drive.driveId(), repoId, branchBuf.get());
                        branchBuf.set("");
                    } catch (Exception e) {
                        MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
                    }
                }
            }
        }

        ImGui.separator();
        MubWidgets.sectionHeader("VERSION TIMELINE");
        if (commits.isEmpty()) {
            MubTheme.textColored(MubTheme.GUTTER, "No snapshots yet. Click + SAVE to create v1.");
            return;
        }
        for (int i = 0; i < commits.size(); i++) {
            VcsCommit c = commits.get(i);
            MubWidgets.termBadge("v" + c.versionNum, SemanticColor.FOCUS);
            ImGui.sameLine();
            MubTheme.textColored(MubTheme.FG, c.authorName.isBlank() ? "Local" : c.authorName);
            ImGui.sameLine();
            MubTheme.textColored(MubTheme.GUTTER, TIME_FMT.format(Instant.ofEpochMilli(c.timestampMs)));
            ImGui.sameLine();
            if (MubWidgets.starButton("ms_" + c.commitId, c.milestone, SemanticColor.WARN)) {
                try { vcs.setMilestone(drive.driveId(), repoId, c.commitId, !c.milestone); } catch (Exception ignored) {}
            }
            MubWidgets.tooltip(c.milestone ? "Unmark Milestone" : "Mark as Milestone",
                    "Milestones are never removed by automatic pruning.");

            MubTheme.textColored(MubTheme.DIM, "  " + c.message);
            if (ft.previewKind() == LiskovFileType.PreviewKind.SCHEMATIC_3D && file.toString().endsWith(".litematic")) {
                if (MubWidgets.termButton("3D PREVIEW##pv_" + c.commitId, SemanticColor.INK_MUTED)) {
                    previewCommitId = c.commitId;
                    previewCommit = c;
                    activeTab = Tab.INSPECT;
                }
                MubWidgets.tooltip("3D Preview Snapshot", "Inspect how this version looked in 3D before restoring.");
                ImGui.sameLine();
            }
            if (i == 0) {
                MubWidgets.termBadge("CURRENT", SemanticColor.MR);
            } else if (!offline && MubWidgets.termButton("RESTORE##rs_" + c.commitId, SemanticColor.FOCUS)) {
                doRestore(drive, file, relPath, repoId, vcs, c.commitId);
            }
            ImGui.separator();
        }
    }

    private void drawSharingTab(DriveMount drive, Path file, String relPath, String repoId, boolean offline) {
        if (drive.isLocal()) {
            MubTheme.textColored(MubTheme.PLAYER, "PRIVATE ON DRIVE: LOCAL");
            MubTheme.textColored(MubTheme.DIM, "Items on LOCAL stay on your PC across all worlds.");
            DriveMount onlineSrv = activeOnlineServer();
            if (onlineSrv != null && MubWidgets.termButton("COPY -> " + onlineSrv.displayName(), SemanticColor.BOT)) {
                try {
                    var res = LiskovFs.get().drives().transfer(drive, relPath, onlineSrv, "");
                    String dstRel = safeRel(onlineSrv, res.destination());
                    String ftId = LiskovFs.get().fileTypes().detect(res.destination()).id();
                    LiskovSyncClient.get().publishPath(null, res.destination(), dstRel, ftId,
                            playerUuid(), playerName(), "Uploaded from LOCAL", false);
                    MubWidgets.ToastHost.push("Uploaded to " + onlineSrv.displayName(), SemanticColor.BOT);
                } catch (Exception e) {
                    MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
                }
            }
            return;
        }

        MubTheme.textColored(MubTheme.FG, "Drive: " + drive.displayName() + (offline ? " (OFFLINE)" : " (ONLINE)"));
        MubTheme.textColored(MubTheme.GUTTER, "Server UUID: " + drive.driveId());
        ImGui.separator();

        String folderId = LiskovSyncClient.get().findFolderIdForRepo(drive.driveId(), repoId);
        SyncWire.RepoEntry entry = findServerEntry(drive.driveId(), repoId);
        if (entry != null && entry.lock != null && entry.lock.locked) {
            MubWidgets.termBadge("LOCKED BY " + entry.lock.lockedByName, SemanticColor.DANGER);
            MubTheme.textColored(MubTheme.DIM, entry.lock.lockReason);
            if (!offline && MubWidgets.termButton("UNLOCK / OP OVERRIDE", SemanticColor.DANGER)) {
                unlockTargetRepoId = repoId;
                unlockTargetFolderId = folderId;
                lockConfirmBuf.set("");
                showUnlockPopup = true;
            }
        } else if (!offline) {
            if (MubWidgets.termButton("LOCK FILE (AUTHOR)", SemanticColor.WARN)) {
                LiskovSyncClient.get().requestLockToggle(folderId, repoId, true, "Locked from Liskov Inspector", "");
            }
            MubWidgets.tooltip("Lock File", "Prevent teammates from overwriting this file until unlocked.");
        }

        if (!offline) {
            ImGui.separator();
            MubWidgets.sectionHeader("ACL PERMISSIONS");
            ImGui.setNextItemWidth(Math.max(120f, ImGui.getContentRegionAvailX()));
            ImGui.inputTextWithHint("##acl_subj", "Player UUID, @team, or *", aclSubjectBuf);
            MubWidgets.tooltip("ACL Subject", "Enter a player UUID, @teamname, or * for server-wide default.");
            for (AclRole r : new AclRole[]{ AclRole.VIEW, AclRole.EDIT, AclRole.ADMIN, AclRole.NONE }) {
                if (r != AclRole.VIEW) ImGui.sameLine();
                SemanticColor tone = switch (r) {
                    case ADMIN -> SemanticColor.FOCUS;
                    case EDIT -> SemanticColor.MR;
                    case VIEW -> SemanticColor.PLAYER;
                    case NONE -> SemanticColor.DANGER;
                };
                if (MubWidgets.termButton(r.name() + "##acl_" + r.name(), tone) && !aclSubjectBuf.get().isBlank()) {
                    String raw = aclSubjectBuf.get().trim();
                    boolean isTeam = raw.startsWith("@");
                    String subj = isTeam ? raw.substring(1) : raw;
                    LiskovSyncClient.get().requestAclMutate(repoId, subj, "", isTeam, r);
                    MubWidgets.ToastHost.push("Sent ACL update: " + raw + " -> " + r.name(), tone);
                }
            }
        }
    }

    private void drawUnlockModal() {
        if (!showUnlockPopup) return;
        ImGui.openPopup("LockGuard OP Override###LockGuardPopup");
        if (ImGui.beginPopupModal("LockGuard OP Override###LockGuardPopup")) {
            MubTheme.textColored(MubTheme.DIM, "Author can unlock directly. Non-author OP must type exact UNLOCK:");
            ImGui.inputTextWithHint("##lock_confirm", "UNLOCK", lockConfirmBuf);
            if (MubWidgets.termButton("CONFIRM UNLOCK", SemanticColor.DANGER)) {
                LiskovSyncClient.get().requestLockToggle(unlockTargetFolderId, unlockTargetRepoId, false, "", lockConfirmBuf.get());
                showUnlockPopup = false;
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (MubWidgets.termButton("CANCEL", SemanticColor.INK_MUTED)) {
                showUnlockPopup = false;
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private VcsCommit snapshotFile(
            DriveMount drive, String relPath, String repoId, String ftId,
            String msg, boolean milestone, byte[] bytes, VcsStore vcs
    ) throws Exception {
        Map<String, byte[]> files = Map.of(Path.of(relPath).getFileName().toString(), bytes);
        VcsCommit c = vcs.snapshot(
                drive.driveId(), repoId, playerUuid(), playerName(), msg, milestone, false, files);
        if (!drive.isLocal() && !drive.isOffline()) {
            String folderId = LiskovSyncClient.get().findFolderIdForRepo(drive.driveId(), repoId);
            LiskovSyncClient.get().pushCommit(folderId, relPath, ftId, c, files);
        }
        return c;
    }

    private void doRestore(DriveMount drive, Path file, String relPath, String repoId, VcsStore vcs, String commitId) {
        try {
            var res = vcs.restore(drive.driveId(), repoId, commitId, playerUuid(), playerName());
            writeSingleOrBundle(file, res.files());
            previewCommitId = null;
            previewCommit = null;
            if (!drive.isLocal() && !drive.isOffline()) {
                String folderId = LiskovSyncClient.get().findFolderIdForRepo(drive.driveId(), repoId);
                String ftId = LiskovFs.get().fileTypes().detect(file).id();
                LiskovSyncClient.get().pushCommit(folderId, relPath, ftId, res.commit(), res.files());
            }
            LiskovReloadBus.fireReload(new LiskovReloadBus.ReloadNotice(
                    drive.driveId(), relPath, res.commit().versionNum, playerName()));
            MubWidgets.ToastHost.push("Restored as v" + res.commit().versionNum, SemanticColor.MR);
        } catch (Exception e) {
            MubWidgets.ToastHost.push("Restore failed: " + e.getMessage(), SemanticColor.DANGER);
        }
    }

    private static void writeSingleOrBundle(Path file, Map<String, byte[]> files) throws Exception {
        if (files.size() == 1) {
            LiskovPaths.writeAtomicBytes(file, files.values().iterator().next());
        } else if (!files.isEmpty()) {
            Path base = file.getParent();
            for (var e : files.entrySet()) {
                LiskovPaths.writeAtomicBytes(LiskovPaths.resolveInside(base, e.getKey()), e.getValue());
            }
        }
    }

    private static DriveMount activeOnlineServer() {
        String id = LiskovSyncClient.get().activeServerId();
        return id == null ? null : LiskovFs.get().drives().get(id);
    }

    private static SyncWire.RepoEntry findServerEntry(String serverId, String repoId) {
        for (SyncWire.FolderSummary fs : LiskovSyncClient.get().foldersOf(serverId)) {
            for (SyncWire.RepoEntry e : fs.entries) {
                if (repoId.equals(e.repoId)) return e;
            }
        }
        return null;
    }

    private static String safeRel(DriveMount drive, Path p) {
        try {
            return drive.rootDir().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.getFileName().toString();
        }
    }

    private static String playerUuid() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null ? mc.player.getUUID().toString() : "local";
    }

    private static String playerName() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null ? mc.player.getGameProfile().name() : "Player";
    }

    void dispose() {
        preview3d.dispose();
    }
}
