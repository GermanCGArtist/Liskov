package dev.liskov.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LiskovFsAndMigrationTest {
    @Test
    void pathSafetyAndAtomicWrites(@TempDir Path tmp) throws Exception {
        assertEquals("_CON.json", LiskovPaths.safeName("CON.json"));
        assertEquals("hello_world", LiskovPaths.safeName("hello<>:\"/\\|?*_world.. "));
        assertThrows(IllegalArgumentException.class, () -> LiskovPaths.resolveInside(tmp, "../escape"));
        assertThrows(IllegalArgumentException.class, () -> LiskovPaths.resolveInside(tmp, "C:\\Windows\\win.ini"));

        Path f = tmp.resolve("sub/test.json");
        LiskovPaths.writeAtomicUtf8(f, "{\"ok\":true}");
        assertEquals("{\"ok\":true}", Files.readString(f));
    }

    @Test
    void nonDestructiveMigrateLegacyOnce(@TempDir Path tmp) throws Exception {
        Path gameDir = tmp.resolve("mc");
        Path legacyCfg = gameDir.resolve("config");

        Path oldSchem = gameDir.resolve("schematics/keep.litematic");
        Path oldVProj = gameDir.resolve("schematics/Projects/Harbor/project.json");
        Path oldMConf = legacyCfg.resolve("manhattan.json");
        Path oldMProj = legacyCfg.resolve("manhattan-projects/smp/canal.json");
        Path oldScript = gameDir.resolve("scripts/build_wall.txt");
        Path oldCap = gameDir.resolve("manhattan-context/smp_areas.txt");
        Path oldHeavy = legacyCfg.resolve("heavymatica/presets/trench.heavymatic");

        LiskovPaths.writeAtomicUtf8(oldSchem, "schem");
        LiskovPaths.writeAtomicUtf8(oldVProj, "{\"v\":1}");
        LiskovPaths.writeAtomicUtf8(oldMConf, "{\"scale\":1}");
        LiskovPaths.writeAtomicUtf8(oldMProj, "{\"id\":\"canal\"}");
        LiskovPaths.writeAtomicUtf8(oldScript, "schem_build(x)");
        LiskovPaths.writeAtomicUtf8(oldCap, "let box1 = box(0,0,0,1,1,1)");
        LiskovPaths.writeAtomicUtf8(oldHeavy, "{\"regions\":[]}");

        LiskovFs fs = LiskovFs.init(gameDir, legacyCfg);
        // Pre-existing file at target must NEVER be overwritten
        Path preExisting = fs.manhattanDir().resolve("scripts/existing.mscript");
        LiskovPaths.writeAtomicUtf8(preExisting, "KEEP_ME");
        LiskovPaths.writeAtomicUtf8(gameDir.resolve("scripts/existing.txt"), "OVERWRITE_ATTEMPT");

        LiskovMigration.Receipt receipt = LiskovMigration.migrateLegacyOnce(fs);
        assertNotNull(receipt);
        assertTrue(Files.isRegularFile(fs.liskovRoot().resolve(".migrated")));

        // Verify copied to dedicated canonical paths & extensions
        assertEquals("schem", Files.readString(fs.schematicsDir().resolve("keep.litematic")));
        assertEquals("{\"v\":1}", Files.readString(fs.schematicsDir().resolve("Projects/Harbor/project.vproj")));
        assertEquals("{\"scale\":1}", Files.readString(fs.manhattanDir().resolve("config/manhattan.json")));
        assertEquals("{\"id\":\"canal\"}", Files.readString(fs.manhattanDir().resolve("projects/smp/canal.mproj")));
        assertEquals("schem_build(x)", Files.readString(fs.manhattanDir().resolve("scripts/build_wall.mscript")));
        assertEquals("let box1 = box(0,0,0,1,1,1)", Files.readString(fs.manhattanDir().resolve("capture/smp_areas.mcap")));
        assertEquals("{\"regions\":[]}", Files.readString(fs.heavymaticaDir().resolve("presets/trench.heavymatic")));
        assertEquals("KEEP_ME", Files.readString(preExisting));

        // Legacy originals stay intact
        assertTrue(Files.isRegularFile(oldSchem));
        assertTrue(Files.isRegularFile(oldScript));
        assertTrue(Files.isRegularFile(oldMProj));

        // Second run is a no-op because .migrated exists
        assertNull(LiskovMigration.migrateLegacyOnce(fs));
    }
}
