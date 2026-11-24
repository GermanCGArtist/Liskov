package dev.liskov.core.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.liskov.core.LiskovPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Persistent server identity stored at {@code <serverWorldRoot>/liskov/server.json}.
 * Keyed by a permanent UUID so Minecraft version updates and host/IP changes keep the exact same client drive.
 */
public final class ServerIdentity {
    public String serverId;
    public String displayName = "Minecraft Server";
    public long createdAtMs;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ServerIdentity loadOrCreate(Path file, String defaultDisplayName) throws IOException {
        if (Files.isRegularFile(file)) {
            try {
                ServerIdentity id = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ServerIdentity.class);
                if (id != null && id.serverId != null && !id.serverId.isBlank()) {
                    if (id.displayName == null || id.displayName.isBlank()) {
                        id.displayName = defaultDisplayName == null || defaultDisplayName.isBlank()
                                ? "Minecraft Server" : defaultDisplayName;
                    }
                    return id;
                }
            } catch (Exception ignored) {
            }
        }
        ServerIdentity id = new ServerIdentity();
        id.serverId = UUID.randomUUID().toString();
        id.displayName = (defaultDisplayName == null || defaultDisplayName.isBlank()) ? "Minecraft Server" : defaultDisplayName;
        id.createdAtMs = System.currentTimeMillis();
        LiskovPaths.writeAtomicUtf8(file, GSON.toJson(id));
        return id;
    }

    public void save(Path file) throws IOException {
        LiskovPaths.writeAtomicUtf8(file, GSON.toJson(this));
    }
}
