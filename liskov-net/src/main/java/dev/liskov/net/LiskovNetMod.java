package dev.liskov.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.liskov.core.acl.AccessEvaluator;
import dev.liskov.core.acl.AclRole;
import dev.liskov.core.acl.LockGuard;
import dev.liskov.core.sync.LiskovServerStore;
import dev.liskov.core.sync.SyncWire;
import dev.liskov.core.vcs.VcsCommit;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Common Fabric entrypoint for Liskov: registers {@code liskov:sync}, manages server-side
 * {@link LiskovServerStore} under {@code <serverWorldRoot>/liskov/}, and provides {@code /liskov} commands.
 */
public final class LiskovNetMod implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("liskov-net");
    private static final Gson GSON = new Gson();
    private static volatile LiskovNetMod instance;

    private volatile LiskovServerStore serverStore;
    private final Map<String, ChunkBuffer> pendingUploads = new ConcurrentHashMap<>();
    private final Map<String, ChunkBuffer> pendingJsonChunks = new ConcurrentHashMap<>();

    private static final class ChunkBuffer {
        final String sha256;
        final byte[][] chunks;
        int received;

        ChunkBuffer(String sha256, int totalChunks) {
            this.sha256 = sha256;
            this.chunks = new byte[Math.max(1, totalChunks)][];
        }

        synchronized byte[] accept(int idx, byte[] part) throws IOException {
            if (idx < 0 || idx >= chunks.length) return null;
            if (chunks[idx] == null) {
                chunks[idx] = part;
                received++;
            }
            if (received < chunks.length) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] c : chunks) {
                if (c != null) out.write(c);
            }
            return out.toByteArray();
        }
    }

    public static LiskovNetMod instance() {
        return instance;
    }

    public LiskovServerStore serverStore() {
        return serverStore;
    }

    @Override
    public void onInitialize() {
        instance = this;
        PayloadTypeRegistry.serverboundPlay().register(LiskovPayload.TYPE, LiskovPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LiskovPayload.TYPE, LiskovPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                Path root = server.getWorldPath(LevelResource.ROOT).resolve("liskov");
                String serverName = server.isSingleplayer() ? "Local LAN World" : "Minecraft Server";
                serverStore = new LiskovServerStore(root, serverName);
                LOG.info("[Liskov] Server store ready: '{}' ({})",
                        serverStore.identity().displayName, serverStore.identity().serverId);
            } catch (IOException e) {
                LOG.error("[Liskov] Failed to initialize server store", e);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            serverStore = null;
            pendingUploads.clear();
            pendingJsonChunks.clear();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            sendHello(player, SyncWire.HELLO);
        });

        ServerPlayNetworking.registerGlobalReceiver(LiskovPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            server.execute(() -> handleC2S(server, player, payload));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> registerCommands(dispatcher));
    }

    private void sendHello(ServerPlayer player, String packetType) {
        LiskovServerStore store = this.serverStore;
        if (store == null) return;
        try {
            boolean isOp = isOp(player);
            SyncWire.HelloBody hello = store.buildHello(player.getUUID().toString(), isOp);
            send(player, packetType, GSON.toJsonTree(hello), null);
        } catch (IOException e) {
            LOG.warn("[Liskov] Failed to send {} to {}", packetType, player.getGameProfile().name(), e);
        }
    }

    private void handleC2S(MinecraftServer server, ServerPlayer player, LiskovPayload payload) {
        LiskovServerStore store = this.serverStore;
        if (store == null) return;
        try {
            JsonObject env = JsonParser.parseString(payload.json()).getAsJsonObject();
            String type = env.has("type") ? env.get("type").getAsString() : "";
            JsonObject body = env.has("body") && env.get("body").isJsonObject()
                    ? env.getAsJsonObject("body") : new JsonObject();
            String uuid = player.getUUID().toString();
            String name = player.getGameProfile().name();
            boolean isOp = isOp(player);

            switch (type) {
                case SyncWire.PUSH_CHECK -> {
                    SyncWire.PushCheckBody req = GSON.fromJson(body, SyncWire.PushCheckBody.class);
                    List<String> missing = store.missingBlobs(req.requiredHashes);
                    if (!missing.isEmpty()) {
                        SyncWire.NeedBlobsBody need = new SyncWire.NeedBlobsBody();
                        need.repoId = req.commit != null ? dev.liskov.core.vcs.VcsStore.repoIdFor(req.relativePath) : "";
                        need.commitId = req.commit != null ? req.commit.commitId : "";
                        need.missingHashes = missing;
                        send(player, SyncWire.NEED_BLOBS, GSON.toJsonTree(need), null);
                    } else {
                        acceptAndBroadcast(server, player, store, uuid, name, isOp, req);
                    }
                }
                case SyncWire.JSON_CHUNK -> {
                    SyncWire.JsonChunkMeta meta = GSON.fromJson(body, SyncWire.JsonChunkMeta.class);
                    if (meta == null || meta.transferId == null) return;
                    String key = uuid + ":" + meta.transferId;
                    ChunkBuffer buf = pendingJsonChunks.computeIfAbsent(key, k -> new ChunkBuffer(meta.transferId, meta.totalChunks));
                    byte[] complete = buf.accept(meta.chunkIndex, payload.data());
                    if (complete != null) {
                        pendingJsonChunks.remove(key);
                        byte[] jsonBytes = meta.compressed
                                ? SyncWire.gunzip(complete, SyncWire.MAX_DECOMPRESSED_BYTES)
                                : complete;
                        handleC2S(server, player, new LiskovPayload(new String(jsonBytes, StandardCharsets.UTF_8)));
                    }
                }
                case SyncWire.BLOB_CHUNK -> {
                    SyncWire.BlobChunkMeta meta = GSON.fromJson(body, SyncWire.BlobChunkMeta.class);
                    String key = uuid + ":" + meta.transferId + ":" + meta.sha256;
                    ChunkBuffer buf = pendingUploads.computeIfAbsent(key, k -> new ChunkBuffer(meta.sha256, meta.totalChunks));
                    byte[] complete = buf.accept(meta.chunkIndex, payload.data());
                    if (complete != null) {
                        pendingUploads.remove(key);
                        byte[] raw = meta.compressed
                                ? SyncWire.gunzip(complete, SyncWire.MAX_DECOMPRESSED_BYTES)
                                : complete;
                        store.storeBlob(meta.sha256, raw);
                    }
                }
                case SyncWire.PUSH_COMMIT -> {
                    SyncWire.PushCheckBody req = GSON.fromJson(body, SyncWire.PushCheckBody.class);
                    acceptAndBroadcast(server, player, store, uuid, name, isOp, req);
                }
                case SyncWire.PULL_REQUEST -> {
                    SyncWire.PullRequestBody req = GSON.fromJson(body, SyncWire.PullRequestBody.class);
                    if (!AccessEvaluator.canView(store.acl(), req.repoId, uuid, isOp)) {
                        sendError(player, "Permission denied: VIEW role required.");
                        return;
                    }
                    if (req.missingHashes != null) {
                        for (String sha : req.missingHashes) {
                            byte[] raw = store.readBlob(sha);
                            if (raw != null) sendBlobInChunks(player, "pull-" + sha.substring(0, 8), sha, raw);
                        }
                    }
                }
                case SyncWire.HISTORY_REQUEST -> {
                    String repoId = body.has("repoId") ? body.get("repoId").getAsString() : "";
                    if (!AccessEvaluator.canView(store.acl(), repoId, uuid, isOp)) {
                        sendError(player, "Permission denied: VIEW role required.");
                        return;
                    }
                    SyncWire.HistoryResponseBody resp = new SyncWire.HistoryResponseBody();
                    resp.repoId = repoId;
                    resp.commits = store.historyOf(repoId);
                    send(player, SyncWire.HISTORY_RESPONSE, GSON.toJsonTree(resp), null);
                }
                case SyncWire.LOCK_MUTATE -> {
                    SyncWire.LockMutateBody req = GSON.fromJson(body, SyncWire.LockMutateBody.class);
                    LockGuard.Decision dec = store.mutateLock(uuid, name, isOp, req);
                    if (!dec.allowed()) {
                        sendError(player, dec.reason());
                    } else {
                        broadcastTreeSnapshot(server, store);
                    }
                }
                case SyncWire.ACL_MUTATE -> {
                    SyncWire.AclMutateBody req = GSON.fromJson(body, SyncWire.AclMutateBody.class);
                    if (req == null || req.targetId == null || req.subjectUuid == null || req.role == null) {
                        sendError(player, "Invalid ACL request.");
                        return;
                    }
                    AclRole role = AclRole.valueOf(req.role.toUpperCase());
                    if (!store.setAclRole(uuid, isOp, req.targetId, req.subjectUuid, req.subjectName, req.isTeam, role)) {
                        sendError(player, "Permission denied: ADMIN role required.");
                    } else {
                        broadcastTreeSnapshot(server, store);
                    }
                }
                case SyncWire.DELETE_REPO -> {
                    SyncWire.RepoDeleteBody req = GSON.fromJson(body, SyncWire.RepoDeleteBody.class);
                    if (req == null) {
                        sendError(player, "Invalid delete request.");
                        return;
                    }
                    LiskovServerStore.DeleteOutcome out = store.deleteRepo(uuid, isOp, req.folderId, req.repoId);
                    if (!out.ok()) {
                        sendError(player, out.error());
                        return;
                    }
                    JsonElement delTree = GSON.toJsonTree(out.deleted());
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        send(p, SyncWire.REPO_DELETED, delTree, null);
                    }
                    broadcastTreeSnapshot(server, store);
                }
                case SyncWire.RENAME_REPO -> {
                    SyncWire.RepoRenameBody req = GSON.fromJson(body, SyncWire.RepoRenameBody.class);
                    if (req == null) {
                        sendError(player, "Invalid rename request.");
                        return;
                    }
                    LiskovServerStore.PushOutcome out = store.renameRepo(uuid, isOp, req.folderId, req.repoId, req.newRelativePath);
                    if (!out.ok()) {
                        sendError(player, out.error());
                        return;
                    }
                    broadcastTreeSnapshot(server, store);
                }
                case SyncWire.FOLDER_MUTATE -> {
                    SyncWire.FolderMutateBody req = GSON.fromJson(body, SyncWire.FolderMutateBody.class);
                    LiskovServerStore.FolderOutcome out = store.mutateFolder(uuid, name, isOp, req);
                    if (!out.ok()) {
                        sendError(player, out.error());
                        return;
                    }
                    broadcastTreeSnapshot(server, store);
                }
                default -> LOG.debug("[Liskov] Unknown C2S packet type: {}", type);
            }
        } catch (Exception e) {
            LOG.warn("[Liskov] Failed handling C2S packet from {}", player.getGameProfile().name(), e);
            sendError(player, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void acceptAndBroadcast(
            MinecraftServer server,
            ServerPlayer sender,
            LiskovServerStore store,
            String uuid,
            String name,
            boolean isOp,
            SyncWire.PushCheckBody req
    ) throws IOException {
        LiskovServerStore.PushOutcome out = store.acceptCommit(uuid, name, isOp, req);
        if (!out.ok()) {
            sendError(sender, out.error());
            return;
        }
        JsonElement updTree = GSON.toJsonTree(out.updated());
        send(sender, SyncWire.PUSH_ACK, updTree, null);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(sender.getUUID())) continue;
            if (AccessEvaluator.canView(store.acl(), out.updated().entry.repoId, p.getUUID().toString(), isOp(p))) {
                send(p, SyncWire.REPO_HEAD_UPDATED, updTree, null);
            }
        }
    }

    private void broadcastTreeSnapshot(MinecraftServer server, LiskovServerStore store) {
        if (server == null || store == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendHello(p, SyncWire.TREE_SNAPSHOT);
        }
    }

    private void sendBlobInChunks(ServerPlayer player, String transferId, String sha256, byte[] raw) {
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
            sendRaw(player, SyncWire.BLOB_CHUNK, GSON.toJsonTree(meta), slices.get(i));
        }
    }

    private void send(ServerPlayer player, String type, JsonElement body, byte[] data) {
        if (!ServerPlayNetworking.canSend(player, LiskovPayload.TYPE)) return;
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
            String transferId = "js-" + UUID.randomUUID().toString().substring(0, 8);
            int total = slices.size();
            for (int i = 0; i < total; i++) {
                SyncWire.JsonChunkMeta meta = new SyncWire.JsonChunkMeta();
                meta.transferId = transferId;
                meta.chunkIndex = i;
                meta.totalChunks = total;
                meta.totalBytes = wireBytes.length;
                meta.compressed = compressed;
                sendRaw(player, SyncWire.JSON_CHUNK, GSON.toJsonTree(meta), slices.get(i));
            }
            return;
        }
        ServerPlayNetworking.send(player, new LiskovPayload(jsonStr, data));
    }

    private void sendRaw(ServerPlayer player, String type, JsonElement body, byte[] data) {
        if (!ServerPlayNetworking.canSend(player, LiskovPayload.TYPE)) return;
        JsonObject env = new JsonObject();
        env.addProperty("v", SyncWire.PROTOCOL_VERSION);
        env.addProperty("type", type);
        env.add("body", body == null ? new JsonObject() : body);
        ServerPlayNetworking.send(player, new LiskovPayload(GSON.toJson(env), data));
    }

    private void sendError(ServerPlayer player, String msg) {
        JsonObject err = new JsonObject();
        err.addProperty("error", msg == null ? "Unknown error" : msg);
        send(player, SyncWire.ERROR, err, null);
    }

    private static boolean isOp(ServerPlayer player) {
        return player != null && player.createCommandSourceStack().permissions()
                .hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
    }

    private static String actorUuid(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer sp ? sp.getUUID().toString() : "console";
    }

    private static String actorName(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer sp ? sp.getGameProfile().name() : "Console";
    }

    private static boolean sourceIsOp(CommandSourceStack src) {
        return src.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("liskov")
                .then(Commands.literal("status").executes(ctx -> {
                    LiskovServerStore s = serverStore;
                    if (s == null) return 0;
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "[Liskov] Server '" + s.identity().displayName + "' (" + s.identity().serverId + ")"), false);
                    return 1;
                }))
                .then(Commands.literal("server")
                        .requires(LiskovNetMod::sourceIsOp)
                        .then(Commands.literal("name")
                                .then(Commands.argument("displayName", StringArgumentType.greedyString()).executes(ctx -> {
                                    LiskovServerStore s = serverStore;
                                    if (s == null) return 0;
                                    String next = StringArgumentType.getString(ctx, "displayName");
                                    try {
                                        s.renameServer(next);
                                        broadcastTreeSnapshot(ctx.getSource().getServer(), s);
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "[Liskov] Renamed server drive to '" + s.identity().displayName + "'"), true);
                                        return 1;
                                    } catch (IOException e) {
                                        ctx.getSource().sendFailure(Component.literal("[Liskov] Rename failed: " + e.getMessage()));
                                        return 0;
                                    }
                                }))))
                .then(Commands.literal("acl")
                        .requires(LiskovNetMod::sourceIsOp)
                        .then(Commands.literal("team")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("team", StringArgumentType.word())
                                                .then(Commands.argument("playerUuid", StringArgumentType.word()).executes(ctx -> {
                                                    LiskovServerStore s = serverStore;
                                                    if (s == null) return 0;
                                                    String team = StringArgumentType.getString(ctx, "team");
                                                    String targetUuid = StringArgumentType.getString(ctx, "playerUuid");
                                                    s.acl().addTeamMember(team, targetUuid);
                                                    try {
                                                        s.saveAcl();
                                                        broadcastTreeSnapshot(ctx.getSource().getServer(), s);
                                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                                "[Liskov ACL] Added " + targetUuid + " to @" + team), true);
                                                        return 1;
                                                    } catch (IOException e) {
                                                        ctx.getSource().sendFailure(Component.literal("[Liskov ACL] Failed: " + e.getMessage()));
                                                        return 0;
                                                    }
                                                })))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("targetId", StringArgumentType.word())
                                        .then(Commands.argument("subject", StringArgumentType.word())
                                                .then(Commands.argument("role", StringArgumentType.word()).executes(ctx -> {
                                                    LiskovServerStore s = serverStore;
                                                    if (s == null) return 0;
                                                    String targetId = StringArgumentType.getString(ctx, "targetId");
                                                    String rawSubject = StringArgumentType.getString(ctx, "subject");
                                                    boolean isTeam = rawSubject.startsWith("@");
                                                    String subject = isTeam ? rawSubject.substring(1) : rawSubject;
                                                    AclRole role;
                                                    try {
                                                        role = AclRole.valueOf(StringArgumentType.getString(ctx, "role").toUpperCase());
                                                    } catch (IllegalArgumentException e) {
                                                        ctx.getSource().sendFailure(Component.literal("[Liskov ACL] Role must be NONE, VIEW, EDIT, or ADMIN"));
                                                        return 0;
                                                    }
                                                    try {
                                                        boolean ok = s.setAclRole(actorUuid(ctx.getSource()), sourceIsOp(ctx.getSource()),
                                                                targetId, subject, "", isTeam, role);
                                                        if (!ok) {
                                                            ctx.getSource().sendFailure(Component.literal("[Liskov ACL] Permission denied."));
                                                            return 0;
                                                        }
                                                        broadcastTreeSnapshot(ctx.getSource().getServer(), s);
                                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                                "[Liskov ACL] " + targetId + ": " + rawSubject + " -> " + role), true);
                                                        return 1;
                                                    } catch (IOException e) {
                                                        ctx.getSource().sendFailure(Component.literal("[Liskov ACL] Failed: " + e.getMessage()));
                                                        return 0;
                                                    }
                                                }))))))
                .then(Commands.literal("lock")
                        .then(Commands.argument("repoId", StringArgumentType.word())
                                .executes(ctx -> executeLock(ctx.getSource(), StringArgumentType.getString(ctx, "repoId"), true, "Locked via /liskov", ""))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> executeLock(ctx.getSource(), StringArgumentType.getString(ctx, "repoId"), true,
                                                StringArgumentType.getString(ctx, "reason"), "")))))
                .then(Commands.literal("unlock")
                        .then(Commands.argument("repoId", StringArgumentType.word())
                                .executes(ctx -> executeLock(ctx.getSource(), StringArgumentType.getString(ctx, "repoId"), false, "", ""))
                                .then(Commands.argument("confirm", StringArgumentType.word())
                                        .executes(ctx -> executeLock(ctx.getSource(), StringArgumentType.getString(ctx, "repoId"), false, "",
                                                StringArgumentType.getString(ctx, "confirm"))))))
                .then(Commands.literal("history")
                        .then(Commands.argument("repoId", StringArgumentType.word()).executes(ctx -> {
                            LiskovServerStore s = serverStore;
                            if (s == null) return 0;
                            String repoId = StringArgumentType.getString(ctx, "repoId");
                            if (!AccessEvaluator.canView(s.acl(), repoId, actorUuid(ctx.getSource()), sourceIsOp(ctx.getSource()))) {
                                ctx.getSource().sendFailure(Component.literal("[Liskov] Permission denied."));
                                return 0;
                            }
                            List<VcsCommit> list = s.historyOf(repoId);
                            if (list.isEmpty()) {
                                ctx.getSource().sendSuccess(() -> Component.literal("[Liskov] No commits for " + repoId), false);
                                return 1;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("[Liskov] History for " + repoId + " (" + list.size() + "):"), false);
                            for (int i = 0; i < Math.min(10, list.size()); i++) {
                                VcsCommit c = list.get(i);
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "  v" + c.versionNum + " #" + c.shortId() + " [" + c.authorName + "] " + c.message), false);
                            }
                            return 1;
                        })))
                .then(Commands.literal("revert")
                        .then(Commands.argument("repoId", StringArgumentType.word())
                                .then(Commands.argument("commitId", StringArgumentType.word()).executes(ctx -> {
                                    LiskovServerStore s = serverStore;
                                    if (s == null) return 0;
                                    String repoId = StringArgumentType.getString(ctx, "repoId");
                                    String commitId = StringArgumentType.getString(ctx, "commitId");
                                    try {
                                        String fid = s.findFolderIdOfRepo(repoId);
                                        LiskovServerStore.PushOutcome out = s.revertRepo(
                                                actorUuid(ctx.getSource()), actorName(ctx.getSource()),
                                                sourceIsOp(ctx.getSource()), fid, repoId, commitId);
                                        if (!out.ok()) {
                                            ctx.getSource().sendFailure(Component.literal("[Liskov] " + out.error()));
                                            return 0;
                                        }
                                        JsonElement updTree = GSON.toJsonTree(out.updated());
                                        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                                            if (AccessEvaluator.canView(s.acl(), repoId, p.getUUID().toString(), isOp(p))) {
                                                send(p, SyncWire.REPO_HEAD_UPDATED, updTree, null);
                                            }
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "[Liskov] Reverted " + repoId + " -> v" + out.updated().entry.versionNum), true);
                                        return 1;
                                    } catch (IOException e) {
                                        ctx.getSource().sendFailure(Component.literal("[Liskov] Revert failed: " + e.getMessage()));
                                        return 0;
                                    }
                                })))));
    }

    private int executeLock(CommandSourceStack src, String repoId, boolean locked, String reason, String confirm) {
        LiskovServerStore s = serverStore;
        if (s == null) return 0;
        SyncWire.LockMutateBody body = new SyncWire.LockMutateBody();
        body.folderId = s.findFolderIdOfRepo(repoId);
        body.repoId = repoId;
        body.locked = locked;
        body.reason = reason;
        body.confirmUnlockText = confirm;
        try {
            LockGuard.Decision dec = s.mutateLock(actorUuid(src), actorName(src), sourceIsOp(src), body);
            if (!dec.allowed()) {
                src.sendFailure(Component.literal("[Liskov LockGuard] " + dec.reason()));
                return 0;
            }
            broadcastTreeSnapshot(src.getServer(), s);
            src.sendSuccess(() -> Component.literal("[Liskov LockGuard] " + dec.reason()), true);
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("[Liskov LockGuard] Failed: " + e.getMessage()));
            return 0;
        }
    }
}
