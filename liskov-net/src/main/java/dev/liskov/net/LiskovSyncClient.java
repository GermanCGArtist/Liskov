package dev.liskov.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.liskov.core.CacheState;
import dev.liskov.core.DriveMount;
import dev.liskov.core.LiskovFs;
import dev.liskov.core.LiskovPaths;
import dev.liskov.core.acl.AclRole;
import dev.liskov.core.sync.LiskovServerStore;
import dev.liskov.core.sync.SyncWire;
import dev.liskov.core.vcs.VcsCommit;
import dev.liskov.core.vcs.VcsStore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Client-side {@code liskov:sync} state machine: mounts persistent {@code serverId} drives on HELLO,
 * handles Smart On-Demand {@code CLOUD -> CACHED} downloads, pushes SHA-256 deltas on save or transfer,
 * syncs remote history/ACL/delete/rename/folder ops, auto-reloads clean open/pinned items on peer saves,
 * and flags conflicts when local edits are dirty.
 */
public final class LiskovSyncClient {
    private static final Logger LOG = LoggerFactory.getLogger("liskov-sync-client");
    private static final Gson GSON = new Gson();
    private static final LiskovSyncClient INSTANCE = new LiskovSyncClient();

    private boolean registered;
    private volatile String activeServerId;
    private volatile String activeServerName;
    private volatile Consumer<String> errorListener;

    private final Map<String, List<SyncWire.FolderSummary>> serverFoldersByServer = new ConcurrentHashMap<>();
    private final Set<String> dirtyRepos = ConcurrentHashMap.newKeySet();
    private final Set<String> openRepos = ConcurrentHashMap.newKeySet();
    private final Map<String, LiskovReloadBus.ConflictNotice> conflicts = new ConcurrentHashMap<>();
    private final Map<String, SyncWire.PushCheckBody> awaitingUpload = new ConcurrentHashMap<>();
    private final Map<String, byte[]> pendingUploadBlobs = new ConcurrentHashMap<>();
    private final Map<String, ByteChunkAcc> incomingChunks = new ConcurrentHashMap<>();
    private final Map<String, ByteChunkAcc> incomingJsonChunks = new ConcurrentHashMap<>();
    private final Map<String, SyncWire.RepoUpdatedBody> pendingAutoApply = new ConcurrentHashMap<>();
    private final Map<String, List<VcsCommit>> remoteHistoryByRepo = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<List<VcsCommit>>>> historyWaiters = new ConcurrentHashMap<>();

    private static final class ByteChunkAcc {
        final byte[][] parts;
        int count;

        ByteChunkAcc(int total) {
            this.parts = new byte[Math.max(1, total)][];
        }

        synchronized byte[] add(int idx, byte[] data) throws IOException {
            if (idx < 0 || idx >= parts.length) return null;
            if (parts[idx] == null) {
                parts[idx] = data;
                count++;
            }
            if (count < parts.length) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] p : parts) if (p != null) out.write(p);
            return out.toByteArray();
        }
    }

    private LiskovSyncClient() {}

    public static LiskovSyncClient get() {
        return INSTANCE;
    }

    public void setErrorListener(Consumer<String> listener) {
        this.errorListener = listener;
    }

    public synchronized void initClientNetworking() {
        if (registered) return;
        registered = true;

        ClientPlayNetworking.registerGlobalReceiver(LiskovPayload.TYPE, (payload, context) ->
                context.client().execute(() -> handleS2C(payload)));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            String prev = activeServerId;
            activeServerId = null;
            activeServerName = null;
            awaitingUpload.clear();
            pendingUploadBlobs.clear();
            incomingChunks.clear();
            incomingJsonChunks.clear();
            if (prev != null && LiskovFs.isInitialized()) {
                LiskovFs.get().drives().markOffline(prev);
            }
        });
    }

    public String activeServerId() {
        return activeServerId;
    }

    public String activeServerName() {
        return activeServerName;
    }

    public List<SyncWire.FolderSummary> foldersOf(String serverId) {
        return serverFoldersByServer.getOrDefault(serverId, List.of());
    }

    public String findFolderIdForRepo(String serverId, String repoId) {
        if (serverId == null || repoId == null) return LiskovServerStore.DEFAULT_FOLDER_ID;
        for (SyncWire.FolderSummary fs : foldersOf(serverId)) {
            if (fs.entries == null) continue;
            for (SyncWire.RepoEntry e : fs.entries) {
                if (repoId.equals(e.repoId)) return fs.folderId != null ? fs.folderId : LiskovServerStore.DEFAULT_FOLDER_ID;
            }
        }
        return LiskovServerStore.DEFAULT_FOLDER_ID;
    }

    public List<VcsCommit> cachedHistory(String repoId) {
        return repoId == null ? List.of() : remoteHistoryByRepo.getOrDefault(repoId, List.of());
    }

    public Map<String, LiskovReloadBus.ConflictNotice> activeConflicts() {
        return Collections.unmodifiableMap(conflicts);
    }

    public void clearConflict(String repoId) {
        if (repoId != null) conflicts.remove(repoId);
    }

    public void setDirty(String repoId, boolean dirty) {
        if (repoId == null) return;
        if (dirty) dirtyRepos.add(repoId);
        else dirtyRepos.remove(repoId);
    }

    public void setOpen(String repoId, boolean open) {
        if (repoId == null) return;
        if (open) openRepos.add(repoId);
        else openRepos.remove(repoId);
    }

    /** Requests an on-demand download of a {@code CLOUD} item on the currently connected server drive. */
    public void requestDownload(SyncWire.RepoEntry entry) {
        if (entry == null || activeServerId == null) return;
        if (!LiskovFs.isInitialized()) return;
        try {
            VcsStore vcs = new VcsStore(LiskovFs.get().vcsRootDir());
            List<String> missing = vcs.missingBlobs(entry.tree.values());
            if (missing.isEmpty()) {
                materializeEntry(activeServerId, entry, vcs);
                return;
            }
            SyncWire.RepoUpdatedBody pending = new SyncWire.RepoUpdatedBody();
            pending.serverId = activeServerId;
            pending.entry = entry;
            pendingAutoApply.put(entry.repoId, pending);

            SyncWire.PullRequestBody pull = new SyncWire.PullRequestBody();
            pull.repoId = entry.repoId;
            pull.commitId = entry.headCommitId;
            pull.missingHashes = missing;
            send(SyncWire.PULL_REQUEST, GSON.toJsonTree(pull), null);
        } catch (IOException e) {
            LOG.warn("[Liskov] Download request failed for {}", entry.relativePath, e);
        }
    }

    /**
     * Pushes a commit on the active server drive using SHA-256 delta negotiation.
     * Any raw payloads passed in {@code files} are cached in client {@link VcsStore} and memory
     * so {@link SyncWire#NEED_BLOBS} always finds every required SHA-256 blob.
     */
    public void pushCommit(String folderId, String relativePath, String fileTypeId, VcsCommit commit, Map<String, byte[]> files) {
        if (activeServerId == null || commit == null) return;
        if (files != null && !files.isEmpty()) {
            try {
                VcsStore vcs = LiskovFs.isInitialized() ? new VcsStore(LiskovFs.get().vcsRootDir()) : null;
                for (byte[] data : files.values()) {
                    if (data == null) continue;
                    String sha = LiskovPaths.sha256Hex(data);
                    pendingUploadBlobs.put(sha, data);
                    if (vcs != null && !vcs.hasBlob(sha)) {
                        vcs.putBlob(data);
                    }
                }
            } catch (IOException e) {
                LOG.warn("[Liskov] Failed caching push blobs for {}", relativePath, e);
            }
        }
        String cleanRel = relativePath == null ? "" : relativePath.replace('\\', '/');
        String repoId = VcsStore.repoIdFor(cleanRel);
        String effectiveFolder = (folderId == null || folderId.isBlank())
                ? findFolderIdForRepo(activeServerId, repoId) : folderId;
        SyncWire.PushCheckBody check = new SyncWire.PushCheckBody();
        check.folderId = effectiveFolder;
        check.relativePath = cleanRel;
        check.fileTypeId = fileTypeId;
        check.commit = commit;
        check.requiredHashes = commit.tree != null ? new ArrayList<>(commit.tree.values()) : List.of();
        awaitingUpload.put(repoId, check);
        send(SyncWire.PUSH_CHECK, GSON.toJsonTree(check), null);
    }

    /**
     * Snapshots a local file or project directory into client {@link VcsStore} and uploads it to the
     * active online server drive in one call.
     */
    public VcsCommit publishPath(
            String folderId,
            Path localFileOrDir,
            String serverRelativePath,
            String fileTypeId,
            String authorUuid,
            String authorName,
            String message,
            boolean autoSave
    ) throws IOException {
        if (activeServerId == null || !LiskovFs.isInitialized()) return null;
        DriveMount mount = LiskovFs.get().drives().get(activeServerId);
        if (mount == null || mount.isOffline()) return null;

        String cleanRel = serverRelativePath.replace('\\', '/');
        Map<String, byte[]> files = collectPayloadFiles(localFileOrDir);
        if (files.isEmpty()) return null;

        Path driveTarget = LiskovPaths.resolveInside(mount.rootDir(), cleanRel);
        if (Files.isDirectory(localFileOrDir)) {
            LiskovPaths.copyDirectoryOverwrite(localFileOrDir, driveTarget);
        } else if (Files.isRegularFile(localFileOrDir)) {
            LiskovPaths.writeAtomicBytes(driveTarget, Files.readAllBytes(localFileOrDir));
        }
        if (mount.cacheStateOf(cleanRel) != CacheState.PINNED_OFFLINE) {
            mount.setCacheState(cleanRel, CacheState.CACHED);
        }
        mount.saveMetadata();

        String repoId = VcsStore.repoIdFor(cleanRel);
        VcsStore vcs = new VcsStore(LiskovFs.get().vcsRootDir());
        VcsCommit commit = vcs.snapshot(
                activeServerId, repoId, authorUuid, authorName, message, false, autoSave, files);
        String effFolder = (folderId == null || folderId.isBlank())
                ? findFolderIdForRepo(activeServerId, repoId) : folderId;
        pushCommit(effFolder, cleanRel, fileTypeId, commit, files);
        return commit;
    }

    public void requestHistory(String repoId) {
        requestHistory(repoId, null);
    }

    public void requestHistory(String repoId, Consumer<List<VcsCommit>> onResult) {
        if (activeServerId == null || repoId == null || repoId.isBlank()) return;
        if (onResult != null) {
            historyWaiters.computeIfAbsent(repoId, k -> Collections.synchronizedList(new ArrayList<>())).add(onResult);
        }
        JsonObject body = new JsonObject();
        body.addProperty("repoId", repoId);
        send(SyncWire.HISTORY_REQUEST, body, null);
    }

    public void requestLockToggle(String folderId, String repoId, boolean lock, String reason, String confirmUnlockText) {
        if (activeServerId == null) return;
        SyncWire.LockMutateBody body = new SyncWire.LockMutateBody();
        body.folderId = (folderId == null || folderId.isBlank()) ? findFolderIdForRepo(activeServerId, repoId) : folderId;
        body.repoId = repoId;
        body.locked = lock;
        body.reason = reason == null ? "" : reason;
        body.confirmUnlockText = confirmUnlockText == null ? "" : confirmUnlockText;
        send(SyncWire.LOCK_MUTATE, GSON.toJsonTree(body), null);
    }

    public void requestAclMutate(String targetId, String subjectUuid, String subjectName, boolean isTeam, AclRole role) {
        if (activeServerId == null || targetId == null || subjectUuid == null || role == null) return;
        SyncWire.AclMutateBody body = new SyncWire.AclMutateBody();
        body.targetId = targetId;
        body.subjectUuid = subjectUuid;
        body.subjectName = subjectName == null ? "" : subjectName;
        body.isTeam = isTeam;
        body.role = role.name();
        send(SyncWire.ACL_MUTATE, GSON.toJsonTree(body), null);
    }

    public void requestDelete(String folderId, String repoId) {
        if (activeServerId == null || repoId == null || repoId.isBlank()) return;
        SyncWire.RepoDeleteBody body = new SyncWire.RepoDeleteBody();
        body.folderId = (folderId == null || folderId.isBlank()) ? findFolderIdForRepo(activeServerId, repoId) : folderId;
        body.repoId = repoId;
        send(SyncWire.DELETE_REPO, GSON.toJsonTree(body), null);
    }

    public void requestRename(String folderId, String repoId, String newRelativePath) {
        if (activeServerId == null || repoId == null || newRelativePath == null) return;
        SyncWire.RepoRenameBody body = new SyncWire.RepoRenameBody();
        body.folderId = (folderId == null || folderId.isBlank()) ? findFolderIdForRepo(activeServerId, repoId) : folderId;
        body.repoId = repoId;
        body.newRelativePath = newRelativePath.replace('\\', '/');
        send(SyncWire.RENAME_REPO, GSON.toJsonTree(body), null);
    }

    public void requestFolderMutate(SyncWire.FolderMutateBody body) {
        if (activeServerId == null || body == null) return;
        send(SyncWire.FOLDER_MUTATE, GSON.toJsonTree(body), null);
    }

    private void handleS2C(LiskovPayload payload) {
        if (!LiskovFs.isInitialized()) return;
        try {
            JsonObject env = JsonParser.parseString(payload.json()).getAsJsonObject();
            String type = env.has("type") ? env.get("type").getAsString() : "";
            JsonObject body = env.has("body") && env.get("body").isJsonObject()
                    ? env.getAsJsonObject("body") : new JsonObject();

            switch (type) {
                case SyncWire.HELLO, SyncWire.TREE_SNAPSHOT -> {
                    SyncWire.HelloBody hello = GSON.fromJson(body, SyncWire.HelloBody.class);
                    activeServerId = hello.serverId;
                    activeServerName = hello.displayName;
                    serverFoldersByServer.put(hello.serverId, hello.folders != null ? hello.folders : List.of());
                    DriveMount mount = LiskovFs.get().drives().mountServerOnline(hello.serverId, hello.displayName);
                    VcsStore vcs = new VcsStore(LiskovFs.get().vcsRootDir());
                    for (SyncWire.FolderSummary fs : serverFoldersByServer.get(hello.serverId)) {
                        if (fs.entries == null) continue;
                        for (SyncWire.RepoEntry entry : fs.entries) {
                            if (mount.isPinnedOffline(entry.relativePath) || openRepos.contains(entry.repoId)) {
                                if (!vcs.missingBlobs(entry.tree.values()).isEmpty()) {
                                    requestDownload(entry);
                                } else {
                                    materializeEntry(hello.serverId, entry, vcs);
                                }
                            }
                        }
                    }
                }
                case SyncWire.NEED_BLOBS -> {
                    SyncWire.NeedBlobsBody need = GSON.fromJson(body, SyncWire.NeedBlobsBody.class);
                    SyncWire.PushCheckBody pending = awaitingUpload.get(need.repoId);
                    if (pending == null) return;
                    VcsStore vcs = new VcsStore(LiskovFs.get().vcsRootDir());
                    for (String sha : need.missingHashes) {
                        byte[] raw = pendingUploadBlobs.get(sha);
                        if (raw == null) raw = vcs.getBlob(sha);
                        if (raw != null) sendBlobInChunks("up-" + sha.substring(0, 8), sha, raw);
                    }
                    send(SyncWire.PUSH_COMMIT, GSON.toJsonTree(pending), null);
                }
                case SyncWire.PUSH_ACK -> {
                    SyncWire.RepoUpdatedBody ack = GSON.fromJson(body, SyncWire.RepoUpdatedBody.class);
                    if (ack != null && ack.entry != null) {
                        SyncWire.PushCheckBody done = awaitingUpload.remove(ack.entry.repoId);
                        if (done != null && done.requiredHashes != null) {
                            for (String sha : done.requiredHashes) pendingUploadBlobs.remove(sha);
                        }
                        updateCachedIndexEntry(ack.serverId, ack.entry);
                    }
                }
                case SyncWire.JSON_CHUNK -> {
                    SyncWire.JsonChunkMeta meta = GSON.fromJson(body, SyncWire.JsonChunkMeta.class);
                    if (meta == null || meta.transferId == null) return;
                    ByteChunkAcc acc = incomingJsonChunks.computeIfAbsent(
                            meta.transferId, k -> new ByteChunkAcc(meta.totalChunks));
                    byte[] complete = acc.add(meta.chunkIndex, payload.data());
                    if (complete != null) {
                        incomingJsonChunks.remove(meta.transferId);
                        byte[] jsonBytes = meta.compressed
                                ? SyncWire.gunzip(complete, SyncWire.MAX_DECOMPRESSED_BYTES)
                                : complete;
                        handleS2C(new LiskovPayload(new String(jsonBytes, StandardCharsets.UTF_8)));
                    }
                }
                case SyncWire.BLOB_CHUNK -> {
                    SyncWire.BlobChunkMeta meta = GSON.fromJson(body, SyncWire.BlobChunkMeta.class);
                    String key = meta.transferId + ":" + meta.sha256;
                    ByteChunkAcc acc = incomingChunks.computeIfAbsent(key, k -> new ByteChunkAcc(meta.totalChunks));
                    byte[] complete = acc.add(meta.chunkIndex, payload.data());
                    if (complete != null) {
                        incomingChunks.remove(key);
                        byte[] raw = meta.compressed
                                ? SyncWire.gunzip(complete, SyncWire.MAX_DECOMPRESSED_BYTES)
                                : complete;
                        VcsStore vcs = new VcsStore(LiskovFs.get().vcsRootDir());
                        vcs.putBlob(raw);
                        tryFlushPendingDownloads(vcs);
                    }
                }
                case SyncWire.REPO_HEAD_UPDATED -> {
                    SyncWire.RepoUpdatedBody upd = GSON.fromJson(body, SyncWire.RepoUpdatedBody.class);
                    if (upd == null || upd.entry == null) return;
                    updateCachedIndexEntry(upd.serverId, upd.entry);
                    if (upd.commit != null) {
                        List<VcsCommit> hist = new ArrayList<>(remoteHistoryByRepo.getOrDefault(upd.entry.repoId, List.of()));
                        hist.removeIf(c -> upd.commit.commitId.equals(c.commitId));
                        hist.add(0, upd.commit);
                        remoteHistoryByRepo.put(upd.entry.repoId, Collections.unmodifiableList(hist));
                    }
                    DriveMount mount = LiskovFs.get().drives().get(upd.serverId);
                    boolean autoInterested = openRepos.contains(upd.entry.repoId)
                            || (mount != null && mount.isPinnedOffline(upd.entry.relativePath));
                    if (!autoInterested) return;

                    if (dirtyRepos.contains(upd.entry.repoId)) {
                        var conflict = new LiskovReloadBus.ConflictNotice(
                                upd.serverId, upd.entry.repoId, upd.entry.relativePath,
                                upd.entry.versionNum, upd.entry.authorName, upd.entry.headCommitId);
                        conflicts.put(upd.entry.repoId, conflict);
                        LiskovReloadBus.fireConflict(conflict);
                        return;
                    }
                    requestDownload(upd.entry);
                }
                case SyncWire.HISTORY_RESPONSE -> {
                    SyncWire.HistoryResponseBody hr = GSON.fromJson(body, SyncWire.HistoryResponseBody.class);
                    if (hr == null || hr.repoId == null) return;
                    List<VcsCommit> list = hr.commits != null ? List.copyOf(hr.commits) : List.of();
                    remoteHistoryByRepo.put(hr.repoId, list);
                    List<Consumer<List<VcsCommit>>> waiters = historyWaiters.remove(hr.repoId);
                    if (waiters != null) {
                        synchronized (waiters) {
                            for (Consumer<List<VcsCommit>> cb : waiters) {
                                try { cb.accept(list); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
                case SyncWire.REPO_DELETED -> {
                    SyncWire.RepoDeletedBody del = GSON.fromJson(body, SyncWire.RepoDeletedBody.class);
                    if (del == null || del.repoId == null) return;
                    removeCachedIndexEntry(del.serverId, del.folderId, del.repoId);
                    conflicts.remove(del.repoId);
                    remoteHistoryByRepo.remove(del.repoId);
                    if (del.relativePath != null && !dirtyRepos.contains(del.repoId)) {
                        DriveMount mount = LiskovFs.get().drives().get(del.serverId);
                        if (mount != null) {
                            try {
                                Path cached = LiskovPaths.resolveInside(mount.rootDir(), del.relativePath);
                                if (Files.isDirectory(cached)) LiskovPaths.deleteRecursive(cached);
                                else Files.deleteIfExists(cached);
                            } catch (Exception ignored) {}
                        }
                    }
                    LiskovReloadBus.fireReload(new LiskovReloadBus.ReloadNotice(
                            del.serverId, del.relativePath, 0, ""));
                }
                case SyncWire.ERROR -> {
                    String msg = body.has("error") ? body.get("error").getAsString() : "Unknown server error";
                    LOG.warn("[Liskov] Server error: {}", msg);
                    Consumer<String> listener = this.errorListener;
                    if (listener != null) {
                        try { listener.accept(msg); } catch (Throwable ignored) {}
                    }
                }
                default -> LOG.debug("[Liskov] Unhandled S2C message: {}", type);
            }
        } catch (Exception e) {
            LOG.warn("[Liskov] Error handling S2C packet", e);
        }
    }

    private static Map<String, byte[]> collectPayloadFiles(Path localFileOrDir) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Path root = localFileOrDir.toAbsolutePath().normalize();
        if (Files.isRegularFile(root)) {
            files.put(root.getFileName().toString(), Files.readAllBytes(root));
        } else if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                    Path rel = root.relativize(p);
                    boolean hidden = false;
                    for (int i = 0; i < rel.getNameCount(); i++) {
                        if (rel.getName(i).toString().startsWith(".")) {
                            hidden = true;
                            break;
                        }
                    }
                    if (!hidden) files.put(rel.toString().replace('\\', '/'), Files.readAllBytes(p));
                }
            }
        }
        return files;
    }

    private void tryFlushPendingDownloads(VcsStore vcs) throws IOException {
        List<String> done = new ArrayList<>();
        for (var entry : pendingAutoApply.entrySet()) {
            SyncWire.RepoUpdatedBody upd = entry.getValue();
            if (vcs.missingBlobs(upd.entry.tree.values()).isEmpty()) {
                materializeEntry(upd.serverId, upd.entry, vcs);
                done.add(entry.getKey());
            }
        }
        for (String id : done) pendingAutoApply.remove(id);
    }

    private void materializeEntry(String serverId, SyncWire.RepoEntry entry, VcsStore vcs) throws IOException {
        DriveMount mount = LiskovFs.get().drives().get(serverId);
        if (mount == null) return;
        if (entry.tree != null && entry.tree.size() == 1) {
            String onlySha = entry.tree.values().iterator().next();
            byte[] raw = vcs.getBlob(onlySha);
            if (raw != null) {
                Path target = LiskovPaths.resolveInside(mount.rootDir(), entry.relativePath);
                LiskovPaths.writeAtomicBytes(target, raw);
            }
        } else if (entry.tree != null) {
            Path targetDir = LiskovPaths.resolveInside(mount.rootDir(), entry.relativePath);
            Path baseDir = Files.isRegularFile(targetDir) || entry.relativePath.endsWith(".vproj")
                    ? targetDir.getParent() : targetDir;
            for (var e : entry.tree.entrySet()) {
                byte[] raw = vcs.getBlob(e.getValue());
                if (raw != null) {
                    Path out = LiskovPaths.resolveInside(baseDir, e.getKey());
                    LiskovPaths.writeAtomicBytes(out, raw);
                }
            }
        }
        if (mount.cacheStateOf(entry.relativePath) != CacheState.PINNED_OFFLINE) {
            mount.setCacheState(entry.relativePath, CacheState.CACHED);
        }
        mount.saveMetadata();
        LiskovReloadBus.fireReload(new LiskovReloadBus.ReloadNotice(
                serverId, entry.relativePath, entry.versionNum, entry.authorName));
    }

    private void updateCachedIndexEntry(String serverId, SyncWire.RepoEntry updated) {
        if (serverId == null || updated == null) return;
        List<SyncWire.FolderSummary> list = serverFoldersByServer.get(serverId);
        if (list == null) return;
        for (SyncWire.FolderSummary fs : list) {
            if (!fs.folderId.equals(updated.folderId)) continue;
            for (int i = 0; i < fs.entries.size(); i++) {
                if (fs.entries.get(i).repoId.equals(updated.repoId)) {
                    fs.entries.set(i, updated);
                    return;
                }
            }
            fs.entries.add(0, updated);
            return;
        }
    }

    private void removeCachedIndexEntry(String serverId, String folderId, String repoId) {
        if (serverId == null || repoId == null) return;
        List<SyncWire.FolderSummary> list = serverFoldersByServer.get(serverId);
        if (list == null) return;
        for (SyncWire.FolderSummary fs : list) {
            if (folderId != null && !folderId.equals(fs.folderId)) continue;
            if (fs.entries != null) fs.entries.removeIf(e -> repoId.equals(e.repoId));
        }
    }

    private void sendBlobInChunks(String transferId, String sha256, byte[] raw) {
        byte[] wireBytes = raw;
        boolean compressed = false;
        try {
            SyncWire.CompressedPayload cp = SyncWire.compressIfSmaller(raw);
            wireBytes = cp.bytes();
            compressed = cp.compressed();
        } catch (IOException e) {
            LOG.warn("[Liskov] Blob compression failed for {}, sending raw", sha256, e);
        }
        List<byte[]> slices = SyncWire.splitChunks(wireBytes, SyncWire.CHUNK_SIZE);
        int total = slices.size();
        for (int i = 0; i < total; i++) {
            SyncWire.BlobChunkMeta meta = new SyncWire.BlobChunkMeta();
            meta.transferId = transferId;
            meta.sha256 = sha256;
            meta.chunkIndex = i;
            meta.totalChunks = total;
            meta.totalBytes = wireBytes.length;
            meta.compressed = compressed;
            sendRaw(SyncWire.BLOB_CHUNK, GSON.toJsonTree(meta), slices.get(i));
        }
    }

    private void send(String type, JsonElement body, byte[] data) {
        if (!ClientPlayNetworking.canSend(LiskovPayload.TYPE)) return;
        JsonObject env = new JsonObject();
        env.addProperty("v", SyncWire.PROTOCOL_VERSION);
        env.addProperty("type", type);
        env.add("body", body == null ? new JsonObject() : body);
        String jsonStr = GSON.toJson(env);
        byte[] jsonBytes = jsonStr.getBytes(StandardCharsets.UTF_8);
        if ((data == null || data.length == 0) && jsonBytes.length > SyncWire.MAX_SINGLE_JSON_BYTES) {
            byte[] wireBytes = jsonBytes;
            boolean compressed = false;
            try {
                SyncWire.CompressedPayload cp = SyncWire.compressIfSmaller(jsonBytes);
                wireBytes = cp.bytes();
                compressed = cp.compressed();
            } catch (IOException e) {
                LOG.warn("[Liskov] JSON envelope compression failed for {}, sending raw chunks", type, e);
            }
            List<byte[]> slices = SyncWire.splitChunks(wireBytes, SyncWire.CHUNK_SIZE);
            String transferId = "jc-" + UUID.randomUUID().toString().substring(0, 8);
            int total = slices.size();
            for (int i = 0; i < total; i++) {
                SyncWire.JsonChunkMeta meta = new SyncWire.JsonChunkMeta();
                meta.transferId = transferId;
                meta.chunkIndex = i;
                meta.totalChunks = total;
                meta.totalBytes = wireBytes.length;
                meta.compressed = compressed;
                sendRaw(SyncWire.JSON_CHUNK, GSON.toJsonTree(meta), slices.get(i));
            }
            return;
        }
        ClientPlayNetworking.send(new LiskovPayload(jsonStr, data));
    }

    private void sendRaw(String type, JsonElement body, byte[] data) {
        if (!ClientPlayNetworking.canSend(LiskovPayload.TYPE)) return;
        JsonObject env = new JsonObject();
        env.addProperty("v", SyncWire.PROTOCOL_VERSION);
        env.addProperty("type", type);
        env.add("body", body == null ? new JsonObject() : body);
        ClientPlayNetworking.send(new LiskovPayload(GSON.toJson(env), data));
    }
}
