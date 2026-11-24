package dev.liskov.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Central pure-Java entry point for the {@code <gameDir>/liskov/} unified filesystem, Drives,
 * file types, and OS Explorer / clipboard helpers.
 */
public final class LiskovFs {
    private static volatile LiskovFs instance;

    private final Path gameDir;
    private final Path legacyConfigDir;
    private final Path liskovRoot;
    private final LiskovDrives drives;
    private final FileTypeRegistry fileTypes = new FileTypeRegistry();
    private final Map<String, LiskovNamespace> namespaces = new LinkedHashMap<>();
    private volatile Consumer<String> clipboardBridge;

    private LiskovFs(Path gameDir, Path legacyConfigDir) throws IOException {
        this.gameDir = gameDir.toAbsolutePath().normalize();
        this.legacyConfigDir = legacyConfigDir.toAbsolutePath().normalize();
        this.liskovRoot = this.gameDir.resolve("liskov");
        Files.createDirectories(this.liskovRoot);
        Files.createDirectories(cacheThumbsDir());
        Files.createDirectories(trashDir());
        Files.createDirectories(vcsRootDir());
        this.drives = new LiskovDrives(this.liskovRoot);
        registerDefaultLocalNamespaces();
    }

    public static synchronized LiskovFs init(Path gameDir, Path legacyConfigDir) throws IOException {
        if (instance == null || !instance.gameDir.equals(gameDir.toAbsolutePath().normalize())) {
            instance = new LiskovFs(gameDir, legacyConfigDir);
        }
        return instance;
    }

    public static LiskovFs get() {
        LiskovFs cur = instance;
        if (cur == null) {
            throw new IllegalStateException("LiskovFs.init(gameDir, legacyConfigDir) has not been called yet.");
        }
        return cur;
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    private void registerDefaultLocalNamespaces() throws IOException {
        Path localRoot = drives.local().rootDir();
        registerNamespace(new LiskovNamespace(
                "schematics", "Schematics", localRoot.resolve("schematics"),
                SemanticColor.PLAYER, "Lm", List.of(".litematic", ".vproj", ".lasm")));
        registerNamespace(new LiskovNamespace(
                "manhattan", "Manhattan", localRoot.resolve("manhattan"),
                SemanticColor.MR, "Mp", List.of(".mproj", ".mscript", ".mcap")));
        registerNamespace(new LiskovNamespace(
                "vitruvius", "Vitruvius", localRoot.resolve("vitruvius"),
                SemanticColor.FOCUS, "Vp", List.of(".vproj", ".lasm")));
        registerNamespace(new LiskovNamespace(
                "heavymatica", "Heavymatica", localRoot.resolve("heavymatica"),
                SemanticColor.CF, "Hm", List.of(".heavymatic")));
    }

    public synchronized LiskovNamespace registerNamespace(LiskovNamespace ns) throws IOException {
        Files.createDirectories(ns.rootDir());
        namespaces.put(ns.id(), ns);
        return ns;
    }

    public synchronized List<LiskovNamespace> namespaces() {
        return new ArrayList<>(namespaces.values());
    }

    public synchronized LiskovNamespace namespace(String id) {
        return namespaces.get(id);
    }

    public Path gameDir() {
        return gameDir;
    }

    public Path legacyConfigDir() {
        return legacyConfigDir;
    }

    public Path liskovRoot() {
        return liskovRoot;
    }

    public Path localRoot() {
        return drives.local().rootDir();
    }

    public Path schematicsDir() {
        return localRoot().resolve("schematics");
    }

    public Path manhattanDir() {
        return localRoot().resolve("manhattan");
    }

    public Path vitruviusDir() {
        return localRoot().resolve("vitruvius");
    }

    public Path heavymaticaDir() {
        return localRoot().resolve("heavymatica");
    }

    public Path trashDir() {
        return liskovRoot.resolve(".trash");
    }

    public Path cacheThumbsDir() {
        return liskovRoot.resolve(".cache").resolve("thumbs");
    }

    public Path vcsRootDir() {
        return liskovRoot.resolve(".liskov-vcs");
    }

    public Path uiConfigFile() {
        return liskovRoot.resolve("liskov.json");
    }

    public Path favoritesFile() {
        return liskovRoot.resolve("favorites.json");
    }

    public LiskovDrives drives() {
        return drives;
    }

    public FileTypeRegistry fileTypes() {
        return fileTypes;
    }

    public void setClipboardBridge(Consumer<String> bridge) {
        this.clipboardBridge = bridge;
    }

    /** Copies {@code path} string to the system clipboard via the UI bridge (or AWT fallback). */
    public boolean copyPathToClipboard(Path path) {
        if (path == null) return false;
        String text = path.toAbsolutePath().normalize().toString();
        Consumer<String> bridge = this.clipboardBridge;
        if (bridge != null) {
            bridge.accept(text);
            return true;
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Opens the OS file manager selecting {@code target} (Windows Explorer / macOS Finder / xdg-open). */
    public boolean openInExplorer(Path target) {
        if (target == null) return false;
        Path abs = target.toAbsolutePath().normalize();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                if (Files.isRegularFile(abs)) {
                    new ProcessBuilder("explorer.exe", "/select," + abs).start();
                } else {
                    Files.createDirectories(abs);
                    new ProcessBuilder("explorer.exe", abs.toString()).start();
                }
                return true;
            } else if (os.contains("mac")) {
                if (Files.isRegularFile(abs)) {
                    new ProcessBuilder("open", "-R", abs.toString()).start();
                } else {
                    Files.createDirectories(abs);
                    new ProcessBuilder("open", abs.toString()).start();
                }
                return true;
            } else {
                Path dir = Files.isDirectory(abs) ? abs : abs.getParent();
                if (dir != null) Files.createDirectories(dir);
                new ProcessBuilder("xdg-open", (dir != null ? dir : abs).toString()).start();
                return true;
            }
        } catch (IOException e) {
            return false;
        }
    }
}
