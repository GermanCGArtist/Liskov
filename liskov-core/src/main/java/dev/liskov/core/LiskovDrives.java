package dev.liskov.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Manages the {@code LOCAL} drive and persistent-UUID {@code Server Drives} under {@code <gameDir>/liskov/}.
 * Enforces Windows-style drag semantics: same-drive transfer = MOVE, cross-drive transfer = COPY, and
 * {@code SERVER_OFFLINE} drives are strictly read-only.
 */
public final class LiskovDrives {
    public static final String LOCAL_DRIVE_ID = "local";

    public enum TransferKind {
        MOVED,
        COPIED
    }

    public record TransferResult(TransferKind kind, Path source, Path destination) {}

    private final Path liskovRoot;
    private final DriveMount localDrive;
    private final Map<String, DriveMount> serverDrives = new LinkedHashMap<>();

    public LiskovDrives(Path liskovRoot) throws IOException {
        this.liskovRoot = liskovRoot.toAbsolutePath().normalize();
        Path localDir = this.liskovRoot.resolve("local");
        Files.createDirectories(localDir);
        Files.createDirectories(this.liskovRoot.resolve("servers"));
        this.localDrive = new DriveMount(LOCAL_DRIVE_ID, "LOCAL", DriveMount.Kind.LOCAL, localDir, null);
        loadExistingServerDrives();
    }

    private void loadExistingServerDrives() throws IOException {
        Path serversDir = liskovRoot.resolve("servers");
        if (!Files.isDirectory(serversDir)) return;
        try (Stream<Path> dirs = Files.list(serversDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String id = dir.getFileName().toString();
                Path metaFile = dir.resolve("drive.json");
                Path root = dir.resolve("root");
                Files.createDirectories(root);
                DriveMount mount = new DriveMount(id, id, DriveMount.Kind.SERVER_OFFLINE, root, metaFile);
                serverDrives.put(id, mount);
            }
        }
    }

    public DriveMount local() {
        return localDrive;
    }

    public synchronized DriveMount get(String driveId) {
        if (LOCAL_DRIVE_ID.equals(driveId)) return localDrive;
        return serverDrives.get(driveId);
    }

    public synchronized List<DriveMount> allDrives() {
        List<DriveMount> out = new ArrayList<>();
        out.add(localDrive);
        out.addAll(serverDrives.values());
        return Collections.unmodifiableList(out);
    }

    public synchronized List<DriveMount> serverDrives() {
        return List.copyOf(serverDrives.values());
    }

    /**
     * Mounts or updates a persistent server drive keyed by {@code serverId} (survives MC updates and IP changes).
     */
    public synchronized DriveMount mountServerOnline(String serverId, String displayName) throws IOException {
        String safeId = LiskovPaths.safeName(serverId);
        Path base = liskovRoot.resolve("servers").resolve(safeId);
        Path root = base.resolve("root");
        Path metaFile = base.resolve("drive.json");
        Files.createDirectories(root);

        DriveMount existing = serverDrives.get(safeId);
        if (existing == null) {
            existing = new DriveMount(safeId, displayName, DriveMount.Kind.SERVER_ONLINE, root, metaFile);
            serverDrives.put(safeId, existing);
        } else {
            existing.setKind(DriveMount.Kind.SERVER_ONLINE);
            existing.setDisplayName(displayName);
        }
        existing.markConnectedNow();
        existing.saveMetadata();
        return existing;
    }

    /** Marks a server drive (or all online server drives when null) as {@code SERVER_OFFLINE} (read-only). */
    public synchronized void markOffline(String serverId) {
        for (DriveMount d : serverDrives.values()) {
            if (serverId == null || d.driveId().equals(serverId)) {
                d.setKind(DriveMount.Kind.SERVER_OFFLINE);
                try {
                    d.saveMetadata();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Transfers a file or folder from {@code srcDrive} to {@code dstDirRelative} inside {@code dstDrive}.
     * Same-drive transfers MOVE; cross-drive transfers COPY (keeping the source intact).
     */
    public synchronized TransferResult transfer(
            DriveMount srcDrive,
            String srcRelative,
            DriveMount dstDrive,
            String dstDirRelative
    ) throws IOException {
        if (srcDrive == null || dstDrive == null) {
            throw new IllegalArgumentException("Source and destination drives are required.");
        }
        if (!dstDrive.isWritable()) {
            throw new IllegalStateException("Destination drive '" + dstDrive.displayName() + "' is OFFLINE (Read-Only).");
        }
        String normSrc = DriveMount.normalizeRel(srcRelative);
        Path src = LiskovPaths.resolveInside(srcDrive.rootDir(), normSrc);
        if (!Files.exists(src)) {
            throw new IllegalArgumentException("Source does not exist: " + src);
        }
        if (srcDrive.isOffline() && Files.isRegularFile(src) && srcDrive.cacheStateOf(normSrc) == CacheState.CLOUD) {
            throw new IllegalStateException("Item is cloud-only on offline drive '" + srcDrive.displayName() + "'.");
        }

        String normDstDir = DriveMount.normalizeRel(dstDirRelative);
        Path dstDir = normDstDir.isEmpty() ? dstDrive.rootDir() : LiskovPaths.resolveInside(dstDrive.rootDir(), normDstDir);
        Files.createDirectories(dstDir);

        String fileName = src.getFileName().toString();
        boolean sameDrive = srcDrive.driveId().equals(dstDrive.driveId());
        if (sameDrive) {
            Path dst = dstDir.resolve(fileName);
            if (dst.equals(src)) {
                return new TransferResult(TransferKind.MOVED, src, dst);
            }
            if (Files.isDirectory(src) && dst.toAbsolutePath().normalize().startsWith(src.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Cannot move a folder into itself.");
            }
            String unique = LiskovPaths.uniqueName(fileName, name -> Files.exists(dstDir.resolve(name)));
            Path finalDst = dstDir.resolve(unique);
            Files.move(src, finalDst, StandardCopyOption.REPLACE_EXISTING);
            return new TransferResult(TransferKind.MOVED, src, finalDst);
        } else {
            String unique = LiskovPaths.uniqueName(fileName, name -> Files.exists(dstDir.resolve(name)));
            Path finalDst = dstDir.resolve(unique);
            if (Files.isDirectory(src)) {
                LiskovPaths.copyDirectoryOverwrite(src, finalDst);
            } else {
                Files.copy(src, finalDst, StandardCopyOption.REPLACE_EXISTING);
            }
            if (dstDrive.kind() == DriveMount.Kind.SERVER_ONLINE) {
                String relDst = dstDrive.rootDir().relativize(finalDst).toString().replace('\\', '/');
                dstDrive.setCacheState(relDst, CacheState.CACHED);
                dstDrive.saveMetadata();
            }
            return new TransferResult(TransferKind.COPIED, src, finalDst);
        }
    }
}
