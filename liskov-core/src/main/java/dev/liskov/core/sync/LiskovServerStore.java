package dev.liskov.core.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.liskov.core.LiskovPaths;
import dev.liskov.core.acl.AccessEvaluator;
import dev.liskov.core.acl.AclConfig;
import dev.liskov.core.acl.AclGroup;
import dev.liskov.core.acl.AclRole;
import dev.liskov.core.acl.LockGuard;
import dev.liskov.core.acl.LockState;
import dev.liskov.core.vcs.VcsCommit;
import dev.liskov.core.vcs.VcsHead;
import dev.liskov.core.vcs.VcsStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Authoritative server-side repository, shared-folder, blob-dedup, and ACL store under {@code <serverWorldRoot>/liskov/}.
 * Pure Java + Gson so both unit tests and {@code liskov-net} can drive it directly.
 */
public final class LiskovServerStore {
    public static final String DEFAULT_FOLDER_ID = "shared";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path serverRoot;
    private final Path sharedFoldersDir;
    private final ServerIdentity identity;
    private final AclConfig aclConfig;
    private final VcsStore vcs;

    public LiskovServerStore(Path serverRoot, String defaultServerName) throws IOException {
        this.serverRoot = serverRoot.toAbsolutePath().normalize();
        this.sharedFoldersDir = this.serverRoot.resolve("shared-folders");
        Files.createDirectories(this.serverRoot);
        Files.createDirectories(this.sharedFoldersDir);
        this.identity = ServerIdentity.loadOrCreate(this.serverRoot.resolve("server.json"), defaultServerName);
        this.aclConfig = AclConfig.loadOrCreate(this.serverRoot.resolve("acl.json"));
        this.vcs = new VcsStore(this.serverRoot.resolve(".liskov-vcs"));
        ensureDefaultFolder();
    }

    public Path serverRoot() {
        return serverRoot;
    }

    public ServerIdentity identity() {
        return identity;
    }

    public void renameServer(String newDisplayName) throws IOException {
        if (newDisplayName == null || newDisplayName.isBlank()) return;
        identity.displayName = newDisplayName.strip();
        identity.save(serverRoot.resolve("server.json"));
    }

    public AclConfig acl() {
        return aclConfig;
    }

    public void saveAcl() throws IOException {
        aclConfig.save(serverRoot.resolve("acl.json"));
    }

    public VcsStore vcs() {
        return vcs;
    }

    private void ensureDefaultFolder() throws IOException {
        Path defDir = sharedFoldersDir.resolve(DEFAULT_FOLDER_ID);
        if (!Files.isRegularFile(defDir.resolve("_meta.json"))) {
            SyncWire.FolderSummary fs = new SyncWire.FolderSummary();
            fs.folderId = DEFAULT_FOLDER_ID;
            fs.name = "Shared Workspace";
            fs.colorRole = "focus";
            saveFolderMeta(fs);
        }
    }

    public synchronized SyncWire.FolderSummary loadFolder(String folderId) {
        String safe = LiskovPaths.safeName(folderId);
        Path dir = sharedFoldersDir.resolve(safe);
        Path metaFile = dir.resolve("_meta.json");
        if (!Files.isRegularFile(metaFile)) return null;
        try {
            SyncWire.FolderSummary fs = GSON.fromJson(Files.readString(metaFile, StandardCharsets.UTF_8), SyncWire.FolderSummary.class);
            if (fs == null) return null;
            if (fs.lock == null) fs.lock = new LockState();
            fs.entries = loadFolderIndex(dir.resolve("_index.json"));
            return fs;
        } catch (Exception e) {
            return null;
        }
    }

    private List<SyncWire.RepoEntry> loadFolderIndex(Path indexFile) {
        if (!Files.isRegularFile(indexFile)) return new ArrayList<>();
        try {
            SyncWire.RepoEntry[] arr = GSON.fromJson(Files.readString(indexFile, StandardCharsets.UTF_8), SyncWire.RepoEntry[].class);
            if (arr != null) return new ArrayList<>(List.of(arr));
        } catch (Exception ignored) {
        }
        return new ArrayList<>();
    }

    public synchronized void saveFolderMeta(SyncWire.FolderSummary folder) throws IOException {
        String safe = LiskovPaths.safeName(folder.folderId);
        Path dir = sharedFoldersDir.resolve(safe);
        Files.createDirectories(dir);
        SyncWire.FolderSummary metaOnly = new SyncWire.FolderSummary();
        metaOnly.folderId = safe;
        metaOnly.name = folder.name;
        metaOnly.colorRole = folder.colorRole;
        metaOnly.ownerUuid = folder.ownerUuid;
        metaOnly.ownerName = folder.ownerName;
        metaOnly.archived = folder.archived;
        metaOnly.lock = folder.lock == null ? new LockState() : folder.lock;
        metaOnly.entries = List.of();
        LiskovPaths.writeAtomicUtf8(dir.resolve("_meta.json"), GSON.toJson(metaOnly));
        if (!Files.isRegularFile(dir.resolve("_index.json"))) {
            LiskovPaths.writeAtomicUtf8(dir.resolve("_index.json"), "[]");
        }
    }

    private void saveFolderIndex(String folderId, List<SyncWire.RepoEntry> entries) throws IOException {
        Path dir = sharedFoldersDir.resolve(LiskovPaths.safeName(folderId));
        Files.createDirectories(dir);
        LiskovPaths.writeAtomicUtf8(dir.resolve("_index.json"), GSON.toJson(entries));
    }

    /** Returns all folders and entries visible to {@code playerUuid}. */
    public synchronized List<SyncWire.FolderSummary> listVisibleFolders(String playerUuid, boolean isOp) throws IOException {
        List<SyncWire.FolderSummary> out = new ArrayList<>();
        if (!Files.isDirectory(sharedFoldersDir)) return out;
        try (Stream<Path> dirs = Files.list(sharedFoldersDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String fid = dir.getFileName().toString();
                if (!AccessEvaluator.canView(aclConfig, fid, playerUuid, isOp)) continue;
                SyncWire.FolderSummary fs = loadFolder(fid);
                if (fs == null) continue;
                List<SyncWire.RepoEntry> visibleEntries = new ArrayList<>();
                for (SyncWire.RepoEntry e : fs.entries) {
                    if (AccessEvaluator.canView(aclConfig, e.repoId, playerUuid, isOp)) {
                        visibleEntries.add(e);
                    }
                }
                fs.entries = visibleEntries;
                out.add(fs);
            }
        }
        return out;
    }

    public synchronized SyncWire.HelloBody buildHello(String playerUuid, boolean isOp) throws IOException {
        SyncWire.HelloBody hello = new SyncWire.HelloBody();
        hello.serverId = identity.serverId;
        hello.displayName = identity.displayName;
        hello.folders = listVisibleFolders(playerUuid, isOp);
        return hello;
    }

    public synchronized List<String> missingBlobs(List<String> required) {
        return vcs.missingBlobs(required);
    }

    public synchronized void storeBlob(String expectedSha256, byte[] rawBytes) throws IOException {
        String actual = LiskovPaths.sha256Hex(rawBytes);
        if (expectedSha256 != null && !expectedSha256.isBlank() && !expectedSha256.equalsIgnoreCase(actual)) {
            throw new IllegalArgumentException("SHA-256 mismatch: expected " + expectedSha256 + " got " + actual);
        }
        vcs.putBlob(rawBytes);
    }

    public synchronized byte[] readBlob(String sha256) throws IOException {
        return vcs.getBlob(sha256);
    }

    public record PushOutcome(boolean ok, String error, SyncWire.RepoUpdatedBody updated) {}

    /**
     * Validates ACL ({@code EDIT}) and {@link LockGuard}, ensures all tree blobs exist on the server,
     * commits the new version into {@link VcsStore}, and updates the folder index.
     */
    public synchronized PushOutcome acceptCommit(
            String actorUuid,
            String actorName,
            boolean isOp,
            SyncWire.PushCheckBody req
    ) throws IOException {
        if (req == null || req.commit == null || req.relativePath == null || req.relativePath.isBlank()) {
            return new PushOutcome(false, "Invalid push request.", null);
        }
        String folderId = (req.folderId == null || req.folderId.isBlank()) ? DEFAULT_FOLDER_ID : LiskovPaths.safeName(req.folderId);
        String repoId = VcsStore.repoIdFor(req.relativePath);

        if (!AccessEvaluator.canEdit(aclConfig, folderId, actorUuid, isOp)
                || !AccessEvaluator.canEdit(aclConfig, repoId, actorUuid, isOp)) {
            return new PushOutcome(false, "Permission denied: EDIT role required.", null);
        }

        SyncWire.FolderSummary folder = loadFolder(folderId);
        if (folder == null) {
            folder = new SyncWire.FolderSummary();
            folder.folderId = folderId;
            folder.name = folderId;
            folder.ownerUuid = actorUuid;
            folder.ownerName = actorName;
            saveFolderMeta(folder);
        }
        if (folder.lock != null && folder.lock.locked && !LockGuard.canModify(folder.lock, actorUuid)) {
            return new PushOutcome(false, "Folder '" + folder.name + "' is locked by " + folder.lock.lockedByName + ".", null);
        }

        SyncWire.RepoEntry existing = null;
        for (SyncWire.RepoEntry e : folder.entries) {
            if (repoId.equals(e.repoId)) {
                existing = e;
                break;
            }
        }
        if (existing != null && existing.lock != null && existing.lock.locked && !LockGuard.canModify(existing.lock, actorUuid)) {
            return new PushOutcome(false, "Item '" + existing.relativePath + "' is locked by " + existing.lock.lockedByName + ".", null);
        }

        List<String> missing = vcs.missingBlobs(req.commit.tree.values());
        if (!missing.isEmpty()) {
            return new PushOutcome(false, "Missing blobs on server: " + missing.size(), null);
        }

        VcsHead head = vcs.loadHead(identity.serverId, repoId);
        VcsCommit c = req.commit;
        c.versionNum = Math.max(head.latestVersion + 1, c.versionNum);
        c.authorUuid = actorUuid == null ? "" : actorUuid;
        c.authorName = actorName == null ? "" : actorName;
        if (c.timestampMs <= 0L) c.timestampMs = System.currentTimeMillis();
        vcs.acceptRemoteCommit(identity.serverId, repoId, c, true);

        long totalSize = 0L;
        for (String sha : c.tree.values()) {
            byte[] b = vcs.getBlob(sha);
            if (b != null) totalSize += b.length;
        }

        if (existing == null) {
            existing = new SyncWire.RepoEntry();
            existing.repoId = repoId;
            existing.folderId = folderId;
            existing.authorUuid = c.authorUuid;
            existing.authorName = c.authorName;
            folder.entries.add(0, existing);
            // Seed per-repo ACL policy owned by creator if not present
            aclConfig.getOrCreatePolicy(repoId, actorUuid, actorName);
            saveAcl();
        }
        existing.relativePath = req.relativePath.replace('\\', '/');
        existing.fileTypeId = req.fileTypeId == null ? "file" : req.fileTypeId;
        existing.versionNum = c.versionNum;
        existing.headCommitId = c.commitId;
        existing.updatedAtMs = c.timestampMs;
        existing.sizeBytes = totalSize;
        existing.tree = new LinkedHashMap<>(c.tree);
        saveFolderIndex(folderId, folder.entries);

        SyncWire.RepoUpdatedBody upd = new SyncWire.RepoUpdatedBody();
        upd.serverId = identity.serverId;
        upd.entry = existing;
        upd.commit = c;
        return new PushOutcome(true, "", upd);
    }

    public synchronized LockGuard.Decision mutateLock(
            String actorUuid,
            String actorName,
            boolean isOp,
            SyncWire.LockMutateBody req
    ) throws IOException {
        if (req == null || req.repoId == null || req.repoId.isBlank()) {
            return new LockGuard.Decision(false, "Missing repoId.");
        }
        String folderId = (req.folderId == null || req.folderId.isBlank()) ? DEFAULT_FOLDER_ID : req.folderId;
        SyncWire.FolderSummary folder = loadFolder(folderId);
        if (folder == null) return new LockGuard.Decision(false, "Folder not found: " + folderId);

        SyncWire.RepoEntry target = null;
        for (SyncWire.RepoEntry e : folder.entries) {
            if (req.repoId.equals(e.repoId)) {
                target = e;
                break;
            }
        }
        if (target == null) return new LockGuard.Decision(false, "Item not found.");
        if (target.lock == null) target.lock = new LockState();

        if (req.locked) {
            if (!AccessEvaluator.canEdit(aclConfig, req.repoId, actorUuid, isOp)) {
                return new LockGuard.Decision(false, "EDIT role required to lock.");
            }
            if (target.lock.locked && !LockGuard.canModify(target.lock, actorUuid) && !isOp) {
                return new LockGuard.Decision(false, "Already locked by " + target.lock.lockedByName);
            }
            target.lock.locked = true;
            if (target.lock.authorUuid == null || target.lock.authorUuid.isBlank()) {
                target.lock.authorUuid = target.authorUuid;
                target.lock.authorName = target.authorName;
            }
            target.lock.lockedByUuid = actorUuid == null ? "" : actorUuid;
            target.lock.lockedByName = actorName == null ? "" : actorName;
            target.lock.lockReason = req.reason == null ? "" : req.reason;
            target.lock.lockedAtMs = System.currentTimeMillis();
            saveFolderIndex(folderId, folder.entries);
            return new LockGuard.Decision(true, "Locked.");
        } else {
            LockGuard.Decision dec = LockGuard.canUnlock(target.lock, actorUuid, isOp, req.confirmUnlockText);
            if (!dec.allowed()) return dec;
            target.lock.locked = false;
            target.lock.lockReason = "";
            target.lock.lockedByUuid = "";
            target.lock.lockedByName = "";
            saveFolderIndex(folderId, folder.entries);
            return dec;
        }
    }

    public synchronized boolean setAclRole(
            String actorUuid,
            boolean isOp,
            String targetId,
            String subjectUuid,
            String subjectName,
            boolean isTeam,
            AclRole role
    ) throws IOException {
        if (!AccessEvaluator.canAdmin(aclConfig, targetId, actorUuid, isOp)) {
            return false;
        }
        AclGroup g = aclConfig.getOrCreatePolicy(targetId, actorUuid, "");
        if (isTeam) {
            g.groupRoles.put(subjectUuid, role);
        } else {
            g.memberRoles.put(subjectUuid, role);
            if (subjectName != null && !subjectName.isBlank()) {
                g.playerNames.put(subjectUuid, subjectName);
            }
        }
        saveAcl();
        return true;
    }

    public synchronized List<VcsCommit> historyOf(String repoId) {
        return vcs.loadCommits(identity.serverId, repoId);
    }
}
