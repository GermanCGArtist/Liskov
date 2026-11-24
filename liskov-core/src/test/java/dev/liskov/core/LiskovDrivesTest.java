package dev.liskov.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LiskovDrivesTest {
    @Test
    void persistentServerDriveSurvivesOfflineAndRename(@TempDir Path tmp) throws Exception {
        LiskovDrives drives = new LiskovDrives(tmp);
        DriveMount smp = drives.mountServerOnline("uuid-smp-1", "Survival SMP");
        assertEquals(DriveMount.Kind.SERVER_ONLINE, smp.kind());
        assertEquals("Survival SMP", smp.displayName());
        assertTrue(smp.isWritable());

        drives.markOffline("uuid-smp-1");
        assertEquals(DriveMount.Kind.SERVER_OFFLINE, smp.kind());
        assertFalse(smp.isWritable());

        // Reload from disk (simulates client restart / MC update / IP change with same serverId)
        LiskovDrives reloaded = new LiskovDrives(tmp);
        DriveMount restored = reloaded.get("uuid-smp-1");
        assertNotNull(restored);
        assertEquals("Survival SMP", restored.displayName());
        assertEquals(DriveMount.Kind.SERVER_OFFLINE, restored.kind());

        // Reconnect with updated server name (no duplicate drive created)
        reloaded.mountServerOnline("uuid-smp-1", "Survival SMP 26.4");
        assertEquals(1, reloaded.serverDrives().size());
        assertEquals("Survival SMP 26.4", reloaded.get("uuid-smp-1").displayName());
    }

    @Test
    void sameDriveMovesAndCrossDriveCopiesAndOfflineBlocksWrites(@TempDir Path tmp) throws Exception {
        LiskovDrives drives = new LiskovDrives(tmp);
        DriveMount local = drives.local();
        DriveMount smp = drives.mountServerOnline("uuid-smp", "Survival SMP");

        Path tower = local.rootDir().resolve("schematics/keep_tower.litematic");
        LiskovPaths.writeAtomicUtf8(tower, "tower-bytes");
        Files.createDirectories(local.rootDir().resolve("schematics/archive"));

        // Cross-drive LOCAL -> Server COPIES (source stays intact!)
        var copyRes = drives.transfer(local, "schematics/keep_tower.litematic", smp, "castle");
        assertEquals(LiskovDrives.TransferKind.COPIED, copyRes.kind());
        assertTrue(Files.isRegularFile(tower));
        assertTrue(Files.isRegularFile(smp.rootDir().resolve("castle/keep_tower.litematic")));
        assertEquals(CacheState.CACHED, smp.cacheStateOf("castle/keep_tower.litematic"));

        // Same-drive LOCAL -> LOCAL/archive MOVES
        var moveRes = drives.transfer(local, "schematics/keep_tower.litematic", local, "schematics/archive");
        assertEquals(LiskovDrives.TransferKind.MOVED, moveRes.kind());
        assertFalse(Files.exists(tower));
        assertTrue(Files.isRegularFile(local.rootDir().resolve("schematics/archive/keep_tower.litematic")));

        // Mark server offline -> writes onto server drive fail cleanly, reads of CACHED items copy to LOCAL!
        drives.markOffline("uuid-smp");
        assertThrows(IllegalStateException.class, () ->
                drives.transfer(local, "schematics/archive/keep_tower.litematic", smp, "castle"));

        var offlineCopyToLocal = drives.transfer(smp, "castle/keep_tower.litematic", local, "schematics");
        assertEquals(LiskovDrives.TransferKind.COPIED, offlineCopyToLocal.kind());
        assertTrue(Files.isRegularFile(local.rootDir().resolve("schematics/keep_tower.litematic")));

        // Cloud-only item on offline drive cannot be copied
        Path cloudStub = smp.rootDir().resolve("castle/cloud_only.litematic");
        LiskovPaths.writeAtomicUtf8(cloudStub, "");
        smp.setCacheState("castle/cloud_only.litematic", CacheState.CLOUD);
        assertThrows(IllegalStateException.class, () ->
                drives.transfer(smp, "castle/cloud_only.litematic", local, "schematics"));
    }
}
