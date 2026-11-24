package dev.liskov.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Non-destructive one-time migration from legacy mod folders into {@code <gameDir>/liskov/local/}.
 * Never deletes or overwrites target files, leaves legacy originals intact as backups, and writes
 * {@code <gameDir>/liskov/.migrated}.
 */
public final class LiskovMigration {
    public static final class Receipt {
        public int version = 1;
        public long migratedAtMs;
        public List<String> copiedFiles = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LiskovMigration() {}

    public static Receipt migrateLegacyOnce(LiskovFs fs) throws IOException {
        Path receiptFile = fs.liskovRoot().resolve(".migrated");
        if (Files.isRegularFile(receiptFile)) {
            return null;
        }

        Receipt receipt = new Receipt();
        receipt.migratedAtMs = System.currentTimeMillis();

        Path gameDir = fs.gameDir();
        Path legacyCfg = fs.legacyConfigDir();

        // 1. Standard Litematica schematics + Vitruvius Projects/ -> local/schematics/
        Path oldSchem = gameDir.resolve("schematics");
        Path newSchem = fs.schematicsDir();
        if (Files.isDirectory(oldSchem)) {
            try (Stream<Path> walk = Files.walk(oldSchem)) {
                for (Path src : walk.filter(Files::isRegularFile).toList()) {
                    String rel = oldSchem.relativize(src).toString().replace('\\', '/');
                    // Upgrade Vitruvius project.json -> project.vproj non-destructively if missing
                    String targetRel = rel.endsWith("/project.json") || rel.equals("project.json")
                            ? rel.substring(0, rel.length() - "project.json".length()) + "project.vproj"
                            : rel;
                    copyIfMissing(src, newSchem.resolve(targetRel), receipt);
                }
            }
        }

        // 2. Manhattan configs -> local/manhattan/config/
        Path mConfigDir = fs.manhattanDir().resolve("config");
        copyIfMissing(legacyCfg.resolve("manhattan.json"), mConfigDir.resolve("manhattan.json"), receipt);
        if (!Files.exists(mConfigDir.resolve("manhattan.json"))) {
            copyIfMissing(legacyCfg.resolve("baritonequeue.json"), mConfigDir.resolve("manhattan.json"), receipt);
        }

        // 3. Manhattan projects -> local/manhattan/projects/ (.json -> .mproj except _*.json)
        Path oldMProj = legacyCfg.resolve("manhattan-projects");
        Path newMProj = fs.manhattanDir().resolve("projects");
        if (Files.isDirectory(oldMProj)) {
            try (Stream<Path> walk = Files.walk(oldMProj)) {
                for (Path src : walk.filter(Files::isRegularFile).toList()) {
                    String rel = oldMProj.relativize(src).toString().replace('\\', '/');
                    String fileName = src.getFileName().toString();
                    String targetRel = (fileName.endsWith(".json") && !fileName.startsWith("_"))
                            ? rel.substring(0, rel.length() - 5) + ".mproj"
                            : rel;
                    copyIfMissing(src, newMProj.resolve(targetRel), receipt);
                }
            }
        }

        // 4. Manhattan scripts -> local/manhattan/scripts/*.mscript
        Path oldScripts = gameDir.resolve("scripts");
        Path newScripts = fs.manhattanDir().resolve("scripts");
        if (Files.isDirectory(oldScripts)) {
            try (Stream<Path> walk = Files.walk(oldScripts)) {
                for (Path src : walk.filter(Files::isRegularFile).toList()) {
                    String rel = oldScripts.relativize(src).toString().replace('\\', '/');
                    String targetRel = rel.endsWith(".txt") ? rel.substring(0, rel.length() - 4) + ".mscript" : rel;
                    copyIfMissing(src, newScripts.resolve(targetRel), receipt);
                }
            }
        }

        // 5. Manhattan capture -> local/manhattan/capture/*.mcap
        Path oldCapture = gameDir.resolve("manhattan-context");
        Path newCapture = fs.manhattanDir().resolve("capture");
        if (Files.isDirectory(oldCapture)) {
            try (Stream<Path> walk = Files.walk(oldCapture)) {
                for (Path src : walk.filter(Files::isRegularFile).toList()) {
                    String rel = oldCapture.relativize(src).toString().replace('\\', '/');
                    String targetRel = rel.endsWith(".txt") ? rel.substring(0, rel.length() - 4) + ".mcap" : rel;
                    copyIfMissing(src, newCapture.resolve(targetRel), receipt);
                }
            }
        }

        // 6. Vitruvius config -> local/vitruvius/config/
        receipt.copiedFiles.addAll(LiskovPaths.copyMissingRecursive(
                legacyCfg.resolve("vitruvius"), fs.vitruviusDir().resolve("config"), null));

        // 7. Heavymatica -> local/heavymatica/
        receipt.copiedFiles.addAll(LiskovPaths.copyMissingRecursive(
                legacyCfg.resolve("heavymatica"), fs.heavymaticaDir(), null));

        LiskovPaths.writeAtomicUtf8(receiptFile, GSON.toJson(receipt));
        return receipt;
    }

    private static void copyIfMissing(Path src, Path dst, Receipt receipt) throws IOException {
        if (!Files.isRegularFile(src) || Files.exists(dst)) return;
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst);
        receipt.copiedFiles.add(dst.toString());
    }
}
