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
 * {@code [INSPECT]} (3D preview + Y-slice / inline quick-edit + cross-mod actions),
 * {@code [HISTORY]} (hidden {@code .liskov-vcs/} timeline + optional {@code [×] BRANCHES} + 3D preview of old saves + Restore),
 * and {@code [SHARING & ACL]} (server identity + LockGuard OP {@code "UNLOCK"} modal).
 */
final class InspectorPane {
    private enum Tab { INSPECT, HISTORY, SHARING }

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

    private Tab activeTab = Tab.INSPECT;
    private boolean showBranches = false;
    private boolean editingText = false;
    private Path editingPath;
    private final ImString editorBuf = new ImString(32 * 1024);
    private final ImString branchBuf = new ImString(64);
    private final ImString snapshotNoteBuf = new ImString(128);
    private final ImString lockConfirmBuf = new ImString(32);
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
        if (win.selectedPath == null || !Files.exists(win.selectedPath) || Files.isDirectory(win.selectedPath)) {
            MubTheme.textColored(MubTheme.GUTTER, "Select a file to inspect its 3D preview, .liskov-vcs/ history, and sharing.");
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
        VcsHead head = vcs.loadHead(drive.driveId(), repoId);
        MubWidgets.termBadge("v" + Math.max(1, head.latestVersion), SemanticColor.FOCUS);
        ImGui.sameLine();
        if (drive.isLocal()) {
            MubWidgets.termBadge("LOCAL", SemanticColor.PLAYER);
        } else {
            MubWidgets.termBadge(cache == CacheState.PINNED_OFFLINE ? "★ KEPT OFFLINE" : "✓ CACHED",
                    cache == CacheState.PINNED_OFFLINE ? SemanticColor.MR : SemanticColor.BOT);
        }
        CompletionTag tag = FileActionRegistry.statusOf(file, ft);
        if (tag != CompletionTag.NOT_STARTED) {
            ImGui.sameLine();
            MubWidgets.termBadge(tag.label(), tag.colorRole());
        }
        ImGui.endGroup();

        ImGui.sameLine();
        boolean isFav = favs.isFavorite(drive.driveId(), relPath);
        if (MubWidgets.termButton(isFav ? "★##fav" : "☆##fav", isFav ? SemanticColor.WARN : SemanticColor.INK_MUTED)) {
            favs.toggleFavorite(drive.driveId(), relPath, file.getFileName().toString(), ft.id(), false);
            try { favs.save(fs.favoritesFile()); } catch (Exception ignored) {}
        }

        ImGui.separator();
        // 3 Inspector Tabs
        float tabW = Math.max(70f, (ImGui.getContentRegionAvailX() - 12f) / 3f);
        if (MubWidgets.termButton("INSPECT", activeTab == Tab.INSPECT ? SemanticColor.FOCUS : SemanticColor.INK_MUTED, tabW, 24f)) {
            activeTab = Tab.INSPECT;
        }
        ImGui.sameLine();
        List<VcsCommit> commits = vcs.loadCommits(drive.driveId(), repoId);
        if (MubWidgets.termButton("HISTORY (" + commits.size() + ")", activeTab == Tab.HISTORY ? SemanticColor.FOCUS : SemanticColor.INK_MUTED, tabW, 24f)) {
            activeTab = Tab.HISTORY;
        }
        ImGui.sameLine();
        if (MubWidgets.termButton("SHARING", activeTab == Tab.SHARING ? SemanticColor.BOT : SemanticColor.INK_MUTED, tabW, 24f)) {
            activeTab = Tab.SHARING;
        }
        ImGui.separator();

        switch (activeTab) {
            case INSPECT -> drawInspectTab(win, drive, file, relPath, repoId, ft, vcs, trash, watcher, offline, cache);
            case HISTORY -> drawHistoryTab(drive, file, relPath, repoId, ft, vcs, head, commits, offline);
            case SHARING -> drawSharingTab(drive, file, relPath, repoId, offline);
        }

        drawUnlockModal();
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
                ImGui.sameLine();
                if (MubWidgets.termButton("LIVE", SemanticColor.INK_MUTED)) {
                    previewCommitId = null;
                    previewCommit = null;
                }
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
                    editorBuf.set(txt.length() > 30000 ? txt.substring(0, 30000) : txt);
                } catch (Exception e) {
                    editorBuf.set("// Unreadable text file: " + e.getMessage());
                }
            }
            MubWidgets.sectionHeader(editingText ? "INLINE EDITOR" : "PREVIEW");
            if (editable) {
                ImGui.sameLine();
                if (!editingText) {
                    if (MubWidgets.termButton("QUICK EDIT", SemanticColor.FOCUS)) editingText = true;
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
                    LiskovFs.get().drives().transfer(drive, relPath, onlineSrv, "");
                    MubWidgets.ToastHost.push("Copied to " + onlineSrv.displayName(), SemanticColor.BOT);
                } catch (Exception e) {
                    MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
                }
            }
        } else {
            if (MubWidgets.termButton("COPY -> LOCAL", SemanticColor.PLAYER)) {
                try {
                    LiskovFs.get().drives().transfer(drive, relPath, LiskovFs.get().drives().local(), "schematics");
                    MubWidgets.ToastHost.push("Copied to LOCAL:/schematics", SemanticColor.PLAYER);
                } catch (Exception e) {
                    MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
                }
            }
            if (!offline) {
                ImGui.sameLine();
                boolean pinned = cache == CacheState.PINNED_OFFLINE;
                if (MubWidgets.termButton(pinned ? "★ KEPT OFFLINE" : "☆ KEEP OFFLINE",
                        pinned ? SemanticColor.MR : SemanticColor.INK_MUTED)) {
                    drive.setPinnedOffline(relPath, !pinned);
                    try { drive.saveMetadata(); } catch (Exception ignored) {}
                }
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

        for (FileActionRegistry.Action act : FileActionRegistry.actionsFor(ft)) {
            if (MubWidgets.termButton(act.label(), act.tone())) {
                act.onClick().accept(file);
            }
        }

        if (MubWidgets.termButton("COPY LLM PATH", SemanticColor.INK_MUTED)) {
            LiskovFs.get().copyPathToClipboard(file);
            MubWidgets.ToastHost.push("Copied path to clipboard", SemanticColor.PLAYER);
        }
        ImGui.sameLine();
        if (MubWidgets.termButton("EXPLORER", SemanticColor.INK_MUTED)) {
            LiskovFs.get().openInExplorer(file);
        }
        if (!offline && !win.viewingTrash && MubWidgets.termButton("TRASH", SemanticColor.DANGER)) {
            try {
                trash.moveToTrash(file, ft.id());
                win.selectedPath = null;
                MubWidgets.ToastHost.push("Moved to .trash/ (recoverable)", SemanticColor.DANGER);
            } catch (Exception e) {
                MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
            }
        }
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
        }

        if (MubWidgets.termToggle("br_toggle", "BRANCHES", showBranches, SemanticColor.FOCUS)) {
            showBranches = !showBranches;
        }
        if (!offline) {
            ImGui.sameLine();
            if (MubWidgets.termButton("PRUNE >30D", SemanticColor.INK_MUTED)) {
                try {
                    int n = vcs.prune(drive.driveId(), repoId, 30, 10);
                    MubWidgets.ToastHost.push("Pruned " + n + " old autosave(s)", SemanticColor.MR);
                } catch (Exception ignored) {}
            }
        }

        if (showBranches) {
            ImGui.separator();
            MubWidgets.sectionHeader("BRANCHES (ON: " + head.activeBranch + ")");
            for (String br : head.branchTips.keySet()) {
                boolean active = br.equals(head.activeBranch);
                MubTheme.textColored(active ? MubTheme.PLAYER : MubTheme.DIM, (active ? "● " : "  ") + br);
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
            if (MubWidgets.termButton((c.milestone ? "★" : "☆") + "##ms_" + c.commitId,
                    c.milestone ? SemanticColor.WARN : SemanticColor.INK_MUTED)) {
                try { vcs.setMilestone(drive.driveId(), repoId, c.commitId, !c.milestone); } catch (Exception ignored) {}
            }

            MubTheme.textColored(MubTheme.DIM, "  " + c.message);
            if (ft.previewKind() == LiskovFileType.PreviewKind.SCHEMATIC_3D && file.toString().endsWith(".litematic")) {
                if (MubWidgets.termButton("3D PREVIEW##pv_" + c.commitId, SemanticColor.INK_MUTED)) {
                    previewCommitId = c.commitId;
                    previewCommit = c;
                    activeTab = Tab.INSPECT;
                }
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
                    LiskovFs.get().drives().transfer(drive, relPath, onlineSrv, "");
                    MubWidgets.ToastHost.push("Copied to " + onlineSrv.displayName(), SemanticColor.BOT);
                } catch (Exception e) {
                    MubWidgets.ToastHost.push(e.getMessage(), SemanticColor.DANGER);
                }
            }
            return;
        }

        MubTheme.textColored(MubTheme.FG, "Drive: " + drive.displayName() + (offline ? " (OFFLINE)" : " (ONLINE)"));
        MubTheme.textColored(MubTheme.GUTTER, "Server UUID: " + drive.driveId());
        ImGui.separator();

        SyncWire.RepoEntry entry = findServerEntry(drive.driveId(), repoId);
        if (entry != null && entry.lock != null && entry.lock.locked) {
            MubWidgets.termBadge("LOCKED BY " + entry.lock.lockedByName, SemanticColor.DANGER);
            MubTheme.textColored(MubTheme.DIM, entry.lock.lockReason);
            if (!offline && MubWidgets.termButton("UNLOCK / OP OVERRIDE", SemanticColor.DANGER)) {
                unlockTargetRepoId = repoId;
                unlockTargetFolderId = entry.folderId != null ? entry.folderId : "shared";
                lockConfirmBuf.set("");
                showUnlockPopup = true;
            }
        } else if (!offline) {
            if (MubWidgets.termButton("LOCK FILE (AUTHOR)", SemanticColor.WARN)) {
                LiskovSyncClient.get().requestLockToggle("shared", repoId, true, "Locked from Liskov Inspector", "");
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
        VcsCommit c = vcs.snapshot(
                drive.driveId(), repoId, playerUuid(), playerName(), msg, milestone, false,
                Map.of(Path.of(relPath).getFileName().toString(), bytes));
        if (!drive.isLocal() && !drive.isOffline()) {
            LiskovSyncClient.get().pushCommit("shared", relPath, ftId, c, Map.of());
        }
        return c;
    }

    private void doRestore(DriveMount drive, Path file, String relPath, String repoId, VcsStore vcs, String commitId) {
        try {
            var res = vcs.restore(drive.driveId(), repoId, commitId, playerUuid(), playerName());
            writeSingleOrBundle(file, res.files());
            previewCommitId = null;
            previewCommit = null;
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
