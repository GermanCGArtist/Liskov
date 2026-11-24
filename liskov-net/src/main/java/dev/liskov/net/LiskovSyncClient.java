package dev.liskov.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.liskov.core.CacheState;
import dev.liskov.core.DriveMount;
import dev.liskov.core.LiskovFs;
import dev.liskov.core.LiskovPaths;
import dev.liskov.core.sync.SyncWire;
import dev.liskov.core.vcs.VcsCommit;
import dev.liskov.core.vcs.VcsStore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side {@code liskov:sync} state machine: mounts persistent {@code serverId} drives on HELLO,
 * handles Smart On-Demand {@code CLOUD -> CACHED} downloads, pushes SHA-256 deltas on save,
 * auto-reloads clean open/pinned items on peer saves, and flags conflicts when local edits are dirty.
 */
public final class LiskovSyncClient {
    private static final Logger LOG = LoggerFactory.getLogger("liskov-sync-client");
    private static final Gson GSON = new Gson();
    private static final LiskovSyncClient INSTANCE = new LiskovSyncClient();

    private boolean registered;
    private volatile String activeServerId;
    private volatile String activeServerName;
    private final Map<String, List<SyncWire.FolderSummary>> serverFoldersByServer = new ConcurrentHashMap<>();
    private final Set<String> dirtyRepos = ConcurrentHashMap.newKeySet();
    private final Set<String> openRepos = ConcurrentHashMap.newKeySet();
    private final Map<String, LiskovReloadBus.ConflictNotice> conflicts = new ConcurrentHashMap<>();
    private final Map<String, SyncWire.PushCheckBody> awaitingUpload = new ConcurrentHashMap<>();
    private final Map<String, ByteChunkAcc> incomingChunks = new ConcurrentHashMap<>();
    private final Map<String, SyncWire.RepoUpdatedBody> pendingAutoApply = new ConcurrentHashMap<>();

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

    public synchronized void initClientNetworking() {
        if (registered) return;
        registered = true;

        ClientPlayNetworking.registerGlobalReceiver(LiskovPayload.TYPE, (payload, context) ->
                context.client().execute(() -> handleS2C(payload)));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            String prev = activeServerId;
            activeServerId = null;
            activeServerName = null;
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

    /** Pushes a new commit on the active server drive using SHA-256 delta negotiation. */
    public void pushCommit(String folderId, String relativePath, String fileTypeId, VcsCommit commit, Map<String, byte[]> files) {
        if (activeServerId == null || commit == null) return;
        SyncWire.PushCheckBody check = new SyncWire.PushCheckBody();
        check.folderId = folderId;
        check.relativePath = relativePath;
        check.fileTypeId = fileTypeId;
        check.commit = commit;
        check.requiredHashes = new ArrayList<>(commit.tree.values());
        String repoId = VcsStore.repoIdFor(relativePath);
        awaitingUpload.put(repoId, check);
        send(SyncWire.PUSH_CHECK, GSON.toJsonTree(check), null);
    }

    public void requestLockToggle(String folderId, String repoId, boolean lock, String reason, String confirmUnlockText) {
        if (activeServerId == null) return;
        SyncWire.LockMutateBody body = new SyncWire.LockMutateBody();
        body.folderId = folderId;
        body.repoId = repoId;
        body.locked = lock;
        body.reason = reason == null ? "" : reason;
        body.confirmUnlockText = confirmUnlockText == null ? "" : confirmUnlockText;
        send(SyncWire.LOCK_MUTATE, GSON.toJsonTree(body), null);
    }

    private void handleS2C(LiskovPayload payload) {
        if (!LiskovFs.isInitialized()) return;
        try {
            JsonObject env = JsonParser.parseString(payload.json()).getAsJsonObject();
            String type = env.has("type") ? env.get("type").getAsString() : "";
            JsonObject body = env.has("body") && env.get("body").isJsonObject()
                    ? env.getAsJsonObject("body") : new JsonObject();

            switch (type) {
                case SyncWire.HELLO -> {
                    SyncWire.HelloBody hello = GSON.fromJson(body, SyncWire.HelloBody.class);
                    activeServerId = hello.serverId;
                    activeServerName = hello.displayName;
                    serverFoldersByServer.put(hello.serverId, hello.folders != null ? hello.folders : List.of());
                    DriveMount mount = LiskovFs.get().drives().mountServerOnline(hello.serverId, hello.displayName);
                    VcsStore vcs = new VcsStore(LiskovFs.get().vcsRootDir());
                    // Auto-sync pinned or open items whose blobs are missing
                    for (SyncWire.FolderSummary fs : serverFoldersByServer.get(hello.serverId)) {
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
                        byte[] raw = vcs.getBlob(sha);
                        if (raw != null) sendBlobInChunks("up-" + sha.substring(0, 8), sha, raw);
                    }
                    send(SyncWire.PUSH_COMMIT, GSON.toJsonTree(pending), null);
                }
                case SyncWire.PUSH_ACK -> {
                    SyncWire.RepoUpdatedBody ack = GSON.fromJson(body, SyncWire.RepoUpdatedBody.class);
                    if (ack != null && ack.entry != null) {
                        awaitingUpload.remove(ack.entry.repoId);
                        updateCachedIndexEntry(ack.serverId, ack.entry);
                    }
                }
                case SyncWire.BLOB_CHUNK -> {
                    SyncWire.BlobChunkMeta meta = GSON.fromJson(body, SyncWire.BlobChunkMeta.class);
                    String key = meta.transferId + ":" + meta.sha256;
                    ByteChunkAcc acc = incomingChunks.computeIfAbsent(key, k -> new ByteChunkAcc(meta.totalChunks));
                    byte[] complete = acc.add(meta.chunkIndex, payload.data());
                    if (complete != null) {
                        incomingChunks.remove(key);
                        VcsStore vcs = new VcsStore(LiskovFs.get().vcsRootDir());
                        vcs.putBlob(complete);
                        tryFlushPendingDownloads(vcs);
                    }
                }
                case SyncWire.REPO_HEAD_UPDATED -> {
                    SyncWire.RepoUpdatedBody upd = GSON.fromJson(body, SyncWire.RepoUpdatedBody.class);
                    if (upd == null || upd.entry == null) return;
                    updateCachedIndexEntry(upd.serverId, upd.entry);
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
                case SyncWire.ERROR -> LOG.warn("[Liskov] Server error: {}", body.get("error"));
                default -> LOG.debug("[Liskov] Unhandled S2C message: {}", type);
            }
        } catch (Exception e) {
            LOG.warn("[Liskov] Error handling S2C packet", e);
        }
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

    private void sendBlobInChunks(String transferId, String sha256, byte[] raw) {
        int total = Math.max(1, (raw.length + SyncWire.CHUNK_SIZE - 1) / SyncWire.CHUNK_SIZE);
        for (int i = 0; i < total; i++) {
            int start = i * SyncWire.CHUNK_SIZE;
            int end = Math.min(raw.length, start + SyncWire.CHUNK_SIZE);
            byte[] slice = new byte[end - start];
            System.arraycopy(raw, start, slice, 0, slice.length);
            SyncWire.BlobChunkMeta meta = new SyncWire.BlobChunkMeta();
            meta.transferId = transferId;
            meta.sha256 = sha256;
            meta.chunkIndex = i;
            meta.totalChunks = total;
            meta.totalBytes = raw.length;
            send(SyncWire.BLOB_CHUNK, GSON.toJsonTree(meta), slice);
        }
    }

    private void send(String type, JsonElement body, byte[] data) {
        if (!ClientPlayNetworking.canSend(LiskovPayload.TYPE)) return;
        JsonObject env = new JsonObject();
        env.addProperty("v", SyncWire.PROTOCOL_VERSION);
        env.addProperty("type", type);
        env.add("body", body == null ? new JsonObject() : body);
        ClientPlayNetworking.send(new LiskovPayload(GSON.toJson(env), data));
    }
}
