package dev.liskov.core.acl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.liskov.core.LiskovPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Server-wide ACL configuration persisted atomically in {@code <serverWorldRoot>/liskov/acl.json}.
 */
public final class AclConfig {
    public int version = 1;
    /** Team name (e.g. "builders") -> member UUIDs */
    public Map<String, Set<String>> teams = new LinkedHashMap<>();
    /** Target key (folderId, repoId, or "default") -> AclGroup policy */
    public Map<String, AclGroup> policies = new LinkedHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static AclConfig loadOrCreate(Path file) {
        AclConfig cfg = null;
        if (Files.isRegularFile(file)) {
            try {
                cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AclConfig.class);
            } catch (Exception ignored) {
            }
        }
        if (cfg == null) cfg = new AclConfig();
        if (cfg.teams == null) cfg.teams = new LinkedHashMap<>();
        if (cfg.policies == null) cfg.policies = new LinkedHashMap<>();
        cfg.ensureDefaultPolicy();
        return cfg;
    }

    public void save(Path file) throws IOException {
        ensureDefaultPolicy();
        LiskovPaths.writeAtomicUtf8(file, GSON.toJson(this));
    }

    public void ensureDefaultPolicy() {
        policies.computeIfAbsent("default", k -> {
            AclGroup g = new AclGroup();
            g.id = "default";
            g.defaultRole = AclRole.EDIT;
            return g;
        });
    }

    public AclGroup policyFor(String targetId) {
        if (targetId != null && policies.containsKey(targetId)) {
            return policies.get(targetId);
        }
        ensureDefaultPolicy();
        return policies.get("default");
    }

    public AclGroup getOrCreatePolicy(String targetId, String ownerUuid, String ownerName) {
        return policies.computeIfAbsent(targetId, k -> {
            AclGroup g = new AclGroup();
            g.id = targetId;
            g.ownerUuid = ownerUuid == null ? "" : ownerUuid;
            g.ownerName = ownerName == null ? "" : ownerName;
            g.defaultRole = AclRole.VIEW;
            if (!g.ownerUuid.isEmpty()) {
                g.memberRoles.put(g.ownerUuid, AclRole.ADMIN);
                if (ownerName != null) g.playerNames.put(g.ownerUuid, ownerName);
            }
            return g;
        });
    }

    public void addTeamMember(String teamName, String playerUuid) {
        if (teamName == null || teamName.isBlank() || playerUuid == null || playerUuid.isBlank()) return;
        teams.computeIfAbsent(teamName.strip(), k -> new LinkedHashSet<>()).add(playerUuid);
    }

    public void removeTeamMember(String teamName, String playerUuid) {
        Set<String> set = teams.get(teamName);
        if (set != null) set.remove(playerUuid);
    }
}
