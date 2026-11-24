package dev.liskov.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Pure-Java path sanitization, atomic writes, SHA-256 hashing, and non-destructive directory copy helpers.
 */
public final class LiskovPaths {
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final int MAX_NAME = 120;

    private LiskovPaths() {}

    /** Writes UTF-8 content atomically using a temporary sibling file and {@code ATOMIC_MOVE} fallback. */
    public static void writeAtomicUtf8(Path target, String content) throws IOException {
        writeAtomicBytes(target, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    /** Writes raw bytes atomically using a temporary sibling file and {@code ATOMIC_MOVE} fallback. */
    public static void writeAtomicBytes(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".liskov-", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Returns a Windows-safe single filename or folder segment. */
    public static String safeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < (name == null ? 0 : name.length()); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || "<>:\"/\\|?*".indexOf(c) >= 0) continue;
            sb.append(c);
        }
        String s = sb.toString().strip();
        if (s.length() > MAX_NAME) {
            int lastDot = s.lastIndexOf('.');
            if (lastDot > 0 && s.length() - lastDot <= 16) {
                String ext = s.substring(lastDot);
                int maxStem = Math.max(1, MAX_NAME - ext.length());
                s = s.substring(0, Math.min(lastDot, maxStem)).strip() + ext;
            } else {
                s = s.substring(0, MAX_NAME).strip();
            }
        }
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "unnamed";
        int dot = s.indexOf('.');
        String stem = dot < 0 ? s : s.substring(0, dot);
        if (RESERVED.contains(stem.strip().toUpperCase(Locale.ROOT))) s = "_" + s;
        return s;
    }

    /** Generates a unique file/folder name by appending {@code " 2"}, {@code " 3"}, ... before the extension. */
    public static String uniqueName(String base, Predicate<String> exists) {
        String name = safeName(base);
        if (!exists.test(name)) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = stem + " " + n + ext;
            if (!exists.test(candidate)) return candidate;
        }
    }

    /** Resolves {@code relative} safely inside {@code base}, rejecting absolute paths and {@code ..} traversal. */
    public static Path resolveInside(Path base, String relative) {
        if (relative == null || relative.isEmpty()
                || relative.startsWith("/") || relative.startsWith("\\")
                || relative.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Not a relative path: " + relative);
        }
        Path b = base.toAbsolutePath().normalize();
        Path r = b.resolve(relative).normalize();
        if (!r.startsWith(b) || r.equals(b)) {
            throw new IllegalArgumentException("Path escapes base directory: " + relative);
        }
        return r;
    }

    /** Computes lowercase hex SHA-256 of {@code bytes}. */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Computes lowercase hex SHA-256 of UTF-8 text. */
    public static String sha256Hex(String text) {
        return sha256Hex((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Recursively copies files from {@code srcDir} to {@code dstDir} only when the target file does not exist.
     * Never deletes or overwrites target files.
     */
    public static List<String> copyMissingRecursive(Path srcDir, Path dstDir, Predicate<Path> filter) throws IOException {
        List<String> copied = new ArrayList<>();
        if (!Files.isDirectory(srcDir)) return copied;
        try (Stream<Path> walk = Files.walk(srcDir)) {
            for (Path src : walk.filter(Files::isRegularFile).toList()) {
                if (filter != null && !filter.test(src)) continue;
                Path rel = srcDir.relativize(src);
                Path dst = dstDir.resolve(rel.toString());
                if (!Files.exists(dst)) {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst);
                    copied.add(dst.toString());
                }
            }
        }
        return copied;
    }

    /** Recursively copies a directory tree, overwriting target files if present. */
    public static void copyDirectoryOverwrite(Path srcDir, Path dstDir) throws IOException {
        if (!Files.isDirectory(srcDir)) return;
        try (Stream<Path> walk = Files.walk(srcDir)) {
            for (Path src : walk.filter(Files::isRegularFile).toList()) {
                Path rel = srcDir.relativize(src);
                Path dst = dstDir.resolve(rel.toString());
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Recursively deletes a directory tree if it exists. */
    public static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount())).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
