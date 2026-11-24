package dev.liskov.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Persists starred/pinned Favorites and recent file history across all Liskov drives in {@code favorites.json}.
 */
public final class FavoritesStore {
    public static final int MAX_RECENT = 20;

    public static final class FavoriteEntry {
        public String driveId;
        public String relativePath;
        public String label;
        public String fileTypeId;
        public boolean folder;
        public long pinnedAtMs;
    }

    public static final class RecentEntry {
        public String driveId;
        public String relativePath;
        public String label;
        public String fileTypeId;
        public long openedAtMs;
    }

    public int version = 1;
    public List<FavoriteEntry> favorites = new ArrayList<>();
    public List<RecentEntry> recent = new ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static FavoritesStore loadOrCreate(Path file) {
        FavoritesStore store = null;
        if (Files.isRegularFile(file)) {
            try {
                store = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FavoritesStore.class);
            } catch (Exception ignored) {
            }
        }
        if (store == null) store = new FavoritesStore();
        if (store.favorites == null) store.favorites = new ArrayList<>();
        if (store.recent == null) store.recent = new ArrayList<>();
        return store;
    }

    public void save(Path file) throws IOException {
        LiskovPaths.writeAtomicUtf8(file, GSON.toJson(this));
    }

    public boolean isFavorite(String driveId, String relativePath) {
        String norm = DriveMount.normalizeRel(relativePath);
        for (FavoriteEntry f : favorites) {
            if (Objects.equals(f.driveId, driveId) && Objects.equals(f.relativePath, norm)) return true;
        }
        return false;
    }

    public boolean toggleFavorite(String driveId, String relativePath, String label, String fileTypeId, boolean folder) {
        String norm = DriveMount.normalizeRel(relativePath);
        for (int i = 0; i < favorites.size(); i++) {
            FavoriteEntry f = favorites.get(i);
            if (Objects.equals(f.driveId, driveId) && Objects.equals(f.relativePath, norm)) {
                favorites.remove(i);
                return false;
            }
        }
        FavoriteEntry e = new FavoriteEntry();
        e.driveId = driveId;
        e.relativePath = norm;
        e.label = label;
        e.fileTypeId = fileTypeId;
        e.folder = folder;
        e.pinnedAtMs = System.currentTimeMillis();
        favorites.add(e);
        return true;
    }

    public void recordOpened(String driveId, String relativePath, String label, String fileTypeId) {
        String norm = DriveMount.normalizeRel(relativePath);
        recent.removeIf(r -> Objects.equals(r.driveId, driveId) && Objects.equals(r.relativePath, norm));
        RecentEntry r = new RecentEntry();
        r.driveId = driveId;
        r.relativePath = norm;
        r.label = label;
        r.fileTypeId = fileTypeId;
        r.openedAtMs = System.currentTimeMillis();
        recent.add(0, r);
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.size() - 1);
        }
    }
}
