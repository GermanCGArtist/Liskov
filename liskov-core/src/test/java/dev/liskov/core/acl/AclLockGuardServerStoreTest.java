package dev.liskov.core.acl;

import dev.liskov.core.LiskovPaths;
import dev.liskov.core.sync.LiskovServerStore;
import dev.liskov.core.sync.ServerIdentity;
import dev.liskov.core.sync.SyncWire;
import dev.liskov.core.vcs.VcsCommit;
import dev.liskov.core.vcs.VcsStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AclLockGuardServerStoreTest {
    @Test
    void lockGuardAuthorVsOpRules() {
        LockState lock = new LockState();
        lock.locked = true;
        lock.authorUuid = "author-1";
        lock.authorName = "Julian";
        lock.lockedByUuid = "author-1";
        lock.lockedByName = "Julian";

        assertTrue(LockGuard.canUnlock(lock, "author-1", false, "").allowed());
        assertFalse(LockGuard.canUnlock(lock, "other", false, "UNLOCK").allowed());
        assertFalse(LockGuard.canUnlock(lock, "op-1", true, "unlock").allowed());
        assertTrue(LockGuard.canUnlock(lock, "op-1", true, "UNLOCK").allowed());
    }

    @Test
    void serverStoreAclAndDeltaSync(@TempDir Path tmp) throws Exception {
        LiskovServerStore store = new LiskovServerStore(tmp.resolve("world/liskov"), "Survival SMP");
        String id1 = store.identity().serverId;
        assertNotNull(id1);

        // Reload server store -> serverId stays identical across restarts/updates
        LiskovServerStore reopened = new LiskovServerStore(tmp.resolve("world/liskov"), "Ignored");
        assertEquals(id1, reopened.identity().serverId);
        assertEquals("Survival SMP", reopened.identity().displayName);

        byte[] payload = "SCHEMATIC_BYTES".getBytes(StandardCharsets.UTF_8);
        String sha = LiskovPaths.sha256Hex(payload);
        assertEquals(List.of(sha), reopened.missingBlobs(List.of(sha)));
        reopened.storeBlob(sha, payload);
        assertTrue(reopened.missingBlobs(List.of(sha)).isEmpty());

        VcsCommit c = new VcsCommit();
        c.commitId = "c1";
        c.versionNum = 1;
        c.branch = "main";
        c.message = "Initial upload";
        c.tree = Map.of("keep.litematic", sha);

        SyncWire.PushCheckBody push = new SyncWire.PushCheckBody();
        push.folderId = "shared";
        push.relativePath = "castle/keep.litematic";
        push.fileTypeId = "litematic";
        push.commit = c;

        var outcome = reopened.acceptCommit("u-julian", "Julian", false, push);
        assertTrue(outcome.ok());
        assertEquals(1, outcome.updated().entry.versionNum);

        // Restrict repo to VIEW for Elena, verify Elena's push fails until granted EDIT
        String repoId = VcsStore.repoIdFor("castle/keep.litematic");
        assertTrue(reopened.setAclRole("u-julian", false, repoId, "*", "*", false, AclRole.VIEW));
        var denied = reopened.acceptCommit("u-elena", "Elena", false, push);
        assertFalse(denied.ok());

        // Lock and verify OP unlock rule through store
        SyncWire.LockMutateBody lockReq = new SyncWire.LockMutateBody();
        lockReq.folderId = "shared";
        lockReq.repoId = repoId;
        lockReq.locked = true;
        lockReq.reason = "Production";
        assertTrue(reopened.mutateLock("u-julian", "Julian", false, lockReq).allowed());

        lockReq.locked = false;
        lockReq.confirmUnlockText = "wrong";
        assertFalse(reopened.mutateLock("u-op", "Admin", true, lockReq).allowed());
        lockReq.confirmUnlockText = "UNLOCK";
        assertTrue(reopened.mutateLock("u-op", "Admin", true, lockReq).allowed());
    }
}
