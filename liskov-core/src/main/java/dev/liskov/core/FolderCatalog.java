package dev.liskov.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages color-coded folders, archive state, sharing flags, and item-to-folder assignments
 * stored atomically in {@code _folders.json}.
 */
public final class FolderCatalog {
    public static final String FILE_NAME = "_folders.json";

    public int version = 1;
    public List<FolderEntry> folders = new ArrayList<>();
    public Map<String, String> itemFolders = new LinkedHashMap<>();

    public static final class FolderEntry {
        public String id;
        public String name;
        public String colorRole = "focus";
        public boolean archived = false;
        public boolean shared = false;
        public List<String> sharedWith = new ArrayList<>();
        public int sortOrder = 0;
        public long updatedAtMs = 0L;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static FolderCatalog loadOrCreate(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        FolderCatalog cat = null;
        if (Files.isRegularFile(file)) {
            try {
                cat = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FolderCatalog.class);
            } catch (Exception ignored) {
            }
        }
        if (cat == null) cat = new FolderCatalog();
        if (cat.folders == null) cat.folders = new ArrayList<>();
        if (cat.itemFolders == null) cat.itemFolders = new LinkedHashMap<>();
        cat.ensureDefault();
        return cat;
    }

    public void save(Path directory) throws IOException {
        ensureDefault();
        LiskovPaths.writeAtomicUtf8(directory.resolve(FILE_NAME), GSON.toJson(this));
    }

    public void ensureDefault() {
        for (FolderEntry f : folders) {
            if ("default".equals(f.id)) return;
        }
        FolderEntry def = new FolderEntry();
        def.id = "default";
        def.name = "Default";
        def.colorRole = SemanticColor.FOCUS.id();
        def.sortOrder = 0;
        def.updatedAtMs = System.currentTimeMillis();
        folders.add(0, def);
    }

    public FolderEntry findFolder(String id) {
        for (FolderEntry f : folders) {
            if (f.id != null && f.id.equals(id)) return f;
        }
        return null;
    }

    public FolderEntry createFolder(String name, SemanticColor colorRole) {
        ensureDefault();
        String base = LiskovPaths.safeName(name).toLowerCase().replace(' ', '_');
        String id = base;
        int n = 2;
        while (findFolder(id) != null) {
            id = base + "_" + n++;
        }
        FolderEntry entry = new FolderEntry();
        entry.id = id;
        entry.name = name == null || name.isBlank() ? id : name.strip();
        entry.colorRole = (colorRole == null ? SemanticColor.FOCUS : colorRole).id();
        entry.sortOrder = folders.size();
        entry.updatedAtMs = System.currentTimeMillis();
        folders.add(entry);
        return entry;
    }

    public boolean renameFolder(String id, String newName) {
        FolderEntry f = findFolder(id);
        if (f == null || newName == null || newName.isBlank()) return false;
        f.name = newName.strip();
        f.updatedAtMs = System.currentTimeMillis();
        return true;
    }

    public boolean setColorRole(String id, SemanticColor colorRole) {
        FolderEntry f = findFolder(id);
        if (f == null || colorRole == null) return false;
        f.colorRole = colorRole.id();
        f.updatedAtMs = System.currentTimeMillis();
        return true;
    }

    public boolean setArchived(String id, boolean archived) {
        if ("default".equals(id)) return false;
        FolderEntry f = findFolder(id);
        if (f == null) return false;
        f.archived = archived;
        f.updatedAtMs = System.currentTimeMillis();
        return true;
    }

    public void assignItem(String itemId, String folderId) {
        if (itemId == null || itemId.isBlank()) return;
        if (folderId == null || findFolder(folderId) == null) {
            itemFolders.put(itemId, "default");
        } else {
            itemFolders.put(itemId, folderId);
        }
    }

    public String folderOfItem(String itemId) {
        String id = itemFolders.get(itemId);
        return id != null && findFolder(id) != null ? id : "default";
    }
}
