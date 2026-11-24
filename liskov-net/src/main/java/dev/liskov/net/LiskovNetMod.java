package dev.liskov.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.liskov.core.LiskovPaths;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            sendHello(player);
        });

        ServerPlayNetworking.registerGlobalReceiver(LiskovPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            server.execute(() -> handleC2S(server, player, payload));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> registerCommands(dispatcher));
    }

    private void sendHello(ServerPlayer player) {
        LiskovServerStore store = this.serverStore;
        if (store == null) return;
        try {
            boolean isOp = isOp(player);
            SyncWire.HelloBody hello = store.buildHello(player.getUUID().toString(), isOp);
            send(player, SyncWire.HELLO, GSON.toJsonTree(hello), null);
        } catch (IOException e) {
            LOG.warn("[Liskov] Failed to send HELLO to {}", player.getGameProfile().name(), e);
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
                case SyncWire.BLOB_CHUNK -> {
                    SyncWire.BlobChunkMeta meta = GSON.fromJson(body, SyncWire.BlobChunkMeta.class);
                    String key = uuid + ":" + meta.transferId + ":" + meta.sha256;
                    ChunkBuffer buf = pendingUploads.computeIfAbsent(key, k -> new ChunkBuffer(meta.sha256, meta.totalChunks));
                    byte[] complete = buf.accept(meta.chunkIndex, payload.data());
                    if (complete != null) {
                        pendingUploads.remove(key);
                        store.storeBlob(meta.sha256, complete);
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
                    List<VcsCommit> commits = store.historyOf(repoId);
                    JsonObject resp = new JsonObject();
                    resp.addProperty("repoId", repoId);
                    resp.add("commits", GSON.toJsonTree(commits));
                    send(player, SyncWire.HISTORY_RESPONSE, resp, null);
                }
                case SyncWire.LOCK_MUTATE -> {
                    SyncWire.LockMutateBody req = GSON.fromJson(body, SyncWire.LockMutateBody.class);
                    LockGuard.Decision dec = store.mutateLock(uuid, name, isOp, req);
                    if (!dec.allowed()) {
                        sendError(player, dec.reason());
                    } else {
                        broadcastHelloSnapshot(server, store);
                    }
                }
                case SyncWire.ACL_MUTATE -> {
                    String targetId = body.get("targetId").getAsString();
                    String subjectUuid = body.get("subjectUuid").getAsString();
                    String subjectName = body.has("subjectName") ? body.get("subjectName").getAsString() : "";
                    boolean isTeam = body.has("isTeam") && body.get("isTeam").getAsBoolean();
                    AclRole role = AclRole.valueOf(body.get("role").getAsString().toUpperCase());
                    if (!store.setAclRole(uuid, isOp, targetId, subjectUuid, subjectName, isTeam, role)) {
                        sendError(player, "Permission denied: ADMIN role required.");
                    } else {
                        broadcastHelloSnapshot(server, store);
                    }
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
        send(sender, SyncWire.PUSH_ACK, GSON.toJsonTree(out.updated()), null);
        JsonElement updTree = GSON.toJsonTree(out.updated());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(sender.getUUID())) continue;
            if (AccessEvaluator.canView(store.acl(), out.updated().entry.repoId, p.getUUID().toString(), isOp(p))) {
                send(p, SyncWire.REPO_HEAD_UPDATED, updTree, null);
            }
        }
    }

    private void broadcastHelloSnapshot(MinecraftServer server, LiskovServerStore store) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendHello(p);
        }
    }

    private void sendBlobInChunks(ServerPlayer player, String transferId, String sha256, byte[] raw) {
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
            send(player, SyncWire.BLOB_CHUNK, GSON.toJsonTree(meta), slice);
        }
    }

    private void send(ServerPlayer player, String type, JsonElement body, byte[] data) {
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
                        .requires(src -> src.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
                        .then(Commands.literal("name")
                                .then(Commands.argument("displayName", StringArgumentType.greedyString()).executes(ctx -> {
                                    LiskovServerStore s = serverStore;
                                    if (s == null) return 0;
                                    String next = StringArgumentType.getString(ctx, "displayName");
                                    try {
                                        s.renameServer(next);
                                        broadcastHelloSnapshot(ctx.getSource().getServer(), s);
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "[Liskov] Renamed server drive to '" + s.identity().displayName + "'"), true);
                                        return 1;
                                    } catch (IOException e) {
                                        ctx.getSource().sendFailure(Component.literal("[Liskov] Rename failed: " + e.getMessage()));
                                        return 0;
                                    }
                                }))))
                .then(Commands.literal("acl")
                        .requires(src -> src.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
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
                                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                                "[Liskov ACL] Added " + targetUuid + " to @" + team), true);
                                                        return 1;
                                                    } catch (IOException e) {
                                                        ctx.getSource().sendFailure(Component.literal("[Liskov ACL] Failed: " + e.getMessage()));
                                                        return 0;
                                                    }
                                                })))))));
    }
}
