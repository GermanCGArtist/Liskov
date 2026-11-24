package dev.liskov.ui.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts and loads the Dear ImGui native binary into {@code <gameDir>/liskov/.cache/natives/} cleanly.
 */
final class ImGuiNatives {
    static final String RESOURCE_DIR = "io/imgui/java/native-bin/";
    private static boolean loaded;

    private ImGuiNatives() {}

    static String resourceName(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("mac") || os.contains("darwin")) return x64 || arm64 ? "libimgui-java64.dylib" : null;
        if (os.contains("win")) return x64 ? "imgui-java64.dll" : null;
        if (os.contains("linux")) return x64 ? "libimgui-java64.so" : null;
        return null;
    }

    static synchronized void load(Path dir, ClassLoader cl) throws Exception {
        if (loaded) return;
        String osName = System.getProperty("os.name", "?");
        String osArch = System.getProperty("os.arch", "?");
        String name = resourceName(osName, osArch);
        if (name == null) {
            throw new IllegalStateException("Liskov ImGui host has no native library for " + osName + " " + osArch);
        }
        byte[] bytes;
        try (InputStream in = cl.getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing ImGui native binary in jar: " + RESOURCE_DIR + name);
            }
            bytes = in.readAllBytes();
        }
        Files.createDirectories(dir);
        String hash = sha256(bytes);
        Path folder = place(dir, name, bytes, hash);

        String before = System.getProperty("imgui.library.path");
        System.setProperty("imgui.library.path", folder.toAbsolutePath().toString());
        try {
            Class.forName("imgui.ImGui", true, cl);
        } finally {
            if (before == null) System.clearProperty("imgui.library.path");
            else System.setProperty("imgui.library.path", before);
        }
        loaded = true;
    }

    private static Path place(Path dir, String name, byte[] bytes, String hash) throws IOException {
        Path target = dir.resolve(name);
        if (sameContent(target, hash)) {
            makeUsable(target);
            return dir;
        }
        try {
            write(dir, target, bytes);
            return dir;
        } catch (IOException e) {
            if (sameContent(target, hash)) return dir;
            Path sub = dir.resolve(hash.substring(0, 12));
            Files.createDirectories(sub);
            Path subTarget = sub.resolve(name);
            if (sameContent(subTarget, hash)) makeUsable(subTarget);
            else write(sub, subTarget, bytes);
            return sub;
        }
    }

    private static void write(Path folder, Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(folder, "imgui-", ".tmp");
        try {
            Files.write(tmp, bytes);
            makeUsable(tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static boolean sameContent(Path p, String hash) {
        try {
            return Files.isRegularFile(p) && hash.equals(sha256(Files.readAllBytes(p)));
        } catch (IOException e) {
            return false;
        }
    }

    private static void makeUsable(Path p) {
        try {
            Files.setPosixFilePermissions(p, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
