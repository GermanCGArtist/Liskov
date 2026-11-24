package dev.liskov.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recoverable soft-delete bin under {@code <gameDir>/liskov/.trash/} with manifest metadata for 1-click Restore.
 */
public final class TrashManager {
    public static final String MANIFEST = "_manifest.json";

    public static final class TrashedItem {
        public String id;
        public String originalPath;
        public String fileName;
        public String fileTypeId;
        public String storedName;
        public long sizeBytes;
        public long deletedAtMs;
        public boolean directory;
    }

    public static final class Manifest {
        public int version = 1;
        public List<TrashedItem> items = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path trashDir;

    public TrashManager(Path trashDir) throws IOException {
        this.trashDir = trashDir.toAbsolutePath().normalize();
        Files.createDirectories(this.trashDir);
    }

    public synchronized Manifest loadManifest() {
        Path f = trashDir.resolve(MANIFEST);
        if (Files.isRegularFile(f)) {
            try {
                Manifest m = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Manifest.class);
                if (m != null && m.items != null) return m;
            } catch (Exception ignored) {
            }
        }
        return new Manifest();
    }

    private void saveManifest(Manifest m) throws IOException {
        LiskovPaths.writeAtomicUtf8(trashDir.resolve(MANIFEST), GSON.toJson(m));
    }

    public synchronized TrashedItem moveToTrash(Path source, String fileTypeId) throws IOException {
        if (source == null || !Files.exists(source)) {
            throw new IllegalArgumentException("Source does not exist: " + source);
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String name = source.getFileName().toString();
        String storedName = id + "_" + LiskovPaths.safeName(name);
        Path dest = trashDir.resolve(storedName);
        boolean dir = Files.isDirectory(source);
        long size = dir ? 0L : Files.size(source);

        Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);

        TrashedItem item = new TrashedItem();
        item.id = id;
        item.originalPath = source.toAbsolutePath().normalize().toString();
        item.fileName = name;
        item.fileTypeId = fileTypeId == null ? "file" : fileTypeId;
        item.storedName = storedName;
        item.sizeBytes = size;
        item.deletedAtMs = System.currentTimeMillis();
        item.directory = dir;

        Manifest m = loadManifest();
        m.items.add(0, item);
        saveManifest(m);
        return item;
    }

    public synchronized Path restore(String trashId) throws IOException {
        Manifest m = loadManifest();
        TrashedItem hit = null;
        for (TrashedItem item : m.items) {
            if (item.id.equals(trashId)) {
                hit = item;
                break;
            }
        }
        if (hit == null) return null;
        Path stored = trashDir.resolve(hit.storedName);
        if (!Files.exists(stored)) {
            m.items.remove(hit);
            saveManifest(m);
            return null;
        }
        Path target = Path.of(hit.originalPath);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            final Path parent = target.getParent();
            String unique = LiskovPaths.uniqueName(target.getFileName().toString(),
                    n -> Files.exists(parent.resolve(n)));
            target = parent.resolve(unique);
        }
        Files.move(stored, target, StandardCopyOption.REPLACE_EXISTING);
        m.items.remove(hit);
        saveManifest(m);
        return target;
    }

    public synchronized void emptyTrash() throws IOException {
        Manifest m = loadManifest();
        for (TrashedItem item : m.items) {
            LiskovPaths.deleteRecursive(trashDir.resolve(item.storedName));
        }
        m.items.clear();
        saveManifest(m);
    }

    public List<TrashedItem> list() {
        return List.copyOf(loadManifest().items);
    }
}
