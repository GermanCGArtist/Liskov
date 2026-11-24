package dev.liskov.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Represents either the private {@code LOCAL} drive or a persistent-UUID {@code Server Drive}.
 */
public final class DriveMount {
    public enum Kind {
        LOCAL,
        SERVER_ONLINE,
        SERVER_OFFLINE
    }

    public static final class DriveMetadata {
        public String driveId;
        public String displayName;
        public long lastConnectedMs;
        public Set<String> pinnedOffline = new LinkedHashSet<>();
        public Map<String, CacheState> cacheStates = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String driveId;
    private String displayName;
    private Kind kind;
    private final Path rootDir;
    private final Path metadataFile;
    private final DriveMetadata meta;

    public DriveMount(String driveId, String displayName, Kind kind, Path rootDir, Path metadataFile) {
        this.driveId = driveId;
        this.displayName = displayName;
        this.kind = kind;
        this.rootDir = rootDir.toAbsolutePath().normalize();
        this.metadataFile = metadataFile;
        this.meta = loadMeta(metadataFile, driveId, displayName);
        if (this.meta.displayName != null && !this.meta.displayName.isBlank() && kind == Kind.SERVER_OFFLINE) {
            this.displayName = this.meta.displayName;
        }
    }

    private static DriveMetadata loadMeta(Path file, String id, String name) {
        if (file != null && Files.isRegularFile(file)) {
            try {
                DriveMetadata m = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), DriveMetadata.class);
                if (m != null) {
                    if (m.pinnedOffline == null) m.pinnedOffline = new LinkedHashSet<>();
                    if (m.cacheStates == null) m.cacheStates = new LinkedHashMap<>();
                    return m;
                }
            } catch (Exception ignored) {
            }
        }
        DriveMetadata m = new DriveMetadata();
        m.driveId = id;
        m.displayName = name;
        return m;
    }

    public String driveId() {
        return driveId;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
            this.meta.displayName = displayName;
        }
    }

    public Kind kind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public boolean isLocal() {
        return kind == Kind.LOCAL;
    }

    public boolean isOffline() {
        return kind == Kind.SERVER_OFFLINE;
    }

    public boolean isWritable() {
        return kind != Kind.SERVER_OFFLINE;
    }

    public Path rootDir() {
        return rootDir;
    }

    public long lastConnectedMs() {
        return meta.lastConnectedMs;
    }

    public void markConnectedNow() {
        meta.lastConnectedMs = System.currentTimeMillis();
    }

    public CacheState cacheStateOf(String relativePath) {
        if (kind == Kind.LOCAL) return CacheState.LOCAL;
        String norm = normalizeRel(relativePath);
        if (meta.pinnedOffline.contains(norm)) return CacheState.PINNED_OFFLINE;
        return meta.cacheStates.getOrDefault(norm, CacheState.CLOUD);
    }

    public void setCacheState(String relativePath, CacheState state) {
        if (kind == Kind.LOCAL) return;
        String norm = normalizeRel(relativePath);
        if (state == CacheState.PINNED_OFFLINE) {
            meta.pinnedOffline.add(norm);
            meta.cacheStates.put(norm, CacheState.PINNED_OFFLINE);
        } else {
            meta.pinnedOffline.remove(norm);
            meta.cacheStates.put(norm, state);
        }
    }

    public boolean isPinnedOffline(String relativePath) {
        return meta.pinnedOffline.contains(normalizeRel(relativePath));
    }

    public void setPinnedOffline(String relativePath, boolean pinned) {
        String norm = normalizeRel(relativePath);
        if (pinned) {
            meta.pinnedOffline.add(norm);
            if (meta.cacheStates.getOrDefault(norm, CacheState.CLOUD) == CacheState.CACHED) {
                meta.cacheStates.put(norm, CacheState.PINNED_OFFLINE);
            }
        } else {
            meta.pinnedOffline.remove(norm);
            if (meta.cacheStates.get(norm) == CacheState.PINNED_OFFLINE) {
                meta.cacheStates.put(norm, CacheState.CACHED);
            }
        }
    }

    public Set<String> pinnedOfflinePaths() {
        return Set.copyOf(meta.pinnedOffline);
    }

    public void saveMetadata() throws IOException {
        if (metadataFile == null) return;
        meta.driveId = driveId;
        meta.displayName = displayName;
        LiskovPaths.writeAtomicUtf8(metadataFile, GSON.toJson(meta));
    }

    public static String normalizeRel(String rel) {
        if (rel == null) return "";
        String s = rel.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }
}
