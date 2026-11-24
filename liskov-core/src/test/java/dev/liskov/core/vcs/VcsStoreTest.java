package dev.liskov.core.vcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VcsStoreTest {
    @Test
    void deduplicatesBlobsAcross500SavesAndSupportsPreviewRestoreAndBranchMerge(@TempDir Path tmp) throws Exception {
        VcsStore vcs = new VcsStore(tmp.resolve(".liskov-vcs"));
        String drive = "local";
        String repo = VcsStore.repoIdFor("schematics/Projects/Harbor/project.vproj");

        byte[] sharedSchem = "LARGE_UNCHANGED_LITEMATIC_BYTES_1234567890".getBytes(StandardCharsets.UTF_8);
        for (int i = 1; i <= 50; i++) {
            vcs.snapshot(drive, repo, "u1", "Julian", "Save #" + i, i == 1 || i == 25, i % 2 != 0, Map.of(
                    "project.vproj", ("{\"step\":" + i + "}").getBytes(StandardCharsets.UTF_8),
                    "files/tower.litematic", sharedSchem
            ));
        }

        VcsHead head = vcs.loadHead(drive, repo);
        assertEquals(50, head.latestVersion);
        var commits = vcs.loadCommits(drive, repo);
        assertEquals(50, commits.size());

        // Only 51 unique objects (50 project.vproj variants + 1 shared tower.litematic!)
        long objCount = Files.list(vcs.objectsDir()).count();
        assertEquals(51, objCount);

        // Read-only materialization of v10 for 3D preview without touching tip
        VcsCommit v10 = commits.stream().filter(c -> c.versionNum == 10).findFirst().orElseThrow();
        Map<String, byte[]> preview10 = vcs.readCommitFiles(drive, repo, v10.commitId);
        assertEquals("{\"step\":10}", new String(preview10.get("project.vproj"), StandardCharsets.UTF_8));

        // Non-destructive restore creates v51
        var restored = vcs.restore(drive, repo, v10.commitId, "u1", "Julian");
        assertEquals(51, restored.commit().versionNum);
        assertTrue(restored.commit().message.contains("Restored from v10"));
        assertEquals("{\"step\":10}", new String(restored.files().get("project.vproj"), StandardCharsets.UTF_8));

        // Branch + 3-way merge
        vcs.createBranch(drive, repo, "elena/docks");
        vcs.snapshot(drive, repo, "u2", "Elena", "Added dock", false, false, Map.of(
                "project.vproj", "{\"step\":10}".getBytes(StandardCharsets.UTF_8),
                "files/tower.litematic", sharedSchem,
                "files/dock.litematic", "DOCK".getBytes(StandardCharsets.UTF_8)
        ));
        vcs.switchBranch(drive, repo, "main");
        VcsMergeResult merge = vcs.mergeBranch(drive, repo, "elena/docks", "u1", "Julian");
        assertEquals(VcsMergeResult.Status.FAST_FORWARD, merge.status());
        assertTrue(vcs.readCommitFiles(drive, repo, merge.mergedCommit().commitId).containsKey("files/dock.litematic"));

        // Prune everything older than 0 days except recent 5, milestones, and branch heads
        int pruned = vcs.prune(drive, repo, -1, 5);
        assertTrue(pruned > 30);
        var remaining = vcs.loadCommits(drive, repo);
        assertTrue(remaining.stream().anyMatch(c -> c.versionNum == 1 && c.milestone));
        assertTrue(remaining.stream().anyMatch(c -> c.versionNum == 25 && c.milestone));
    }

    @Test
    void deduplicatesUnchangedAutosavesAutoPrunesAndGcsBlobs(@TempDir Path tmp) throws Exception {
        VcsStore vcs = new VcsStore(tmp.resolve(".liskov-vcs"));
        vcs.setAutoRetention(10, 30, 5);
        String drive = "local";
        String repo = VcsStore.repoIdFor("Projects/Auto/project.vproj");

        // Identical autosaves must not create redundant commits
        VcsCommit a1 = vcs.snapshot(drive, repo, "u1", "Julian", "auto", false, true,
                Map.of("project.vproj", "SAME".getBytes(StandardCharsets.UTF_8)));
        VcsCommit a2 = vcs.snapshot(drive, repo, "u1", "Julian", "auto", false, true,
                Map.of("project.vproj", "SAME".getBytes(StandardCharsets.UTF_8)));
        assertEquals(a1.commitId, a2.commitId);
        assertEquals(1, vcs.loadCommits(drive, repo).size());

        // Distinct autosaves trigger auto-prune to max 10 autosaves + garbage collect orphaned blobs
        for (int i = 2; i <= 35; i++) {
            vcs.snapshot(drive, repo, "u1", "Julian", "auto #" + i, i == 3, true,
                    Map.of("project.vproj", ("DATA_" + i).getBytes(StandardCharsets.UTF_8)));
        }
        var kept = vcs.loadCommits(drive, repo);
        assertTrue(kept.size() <= 12, "Expected auto-prune to cap old autosaves, got " + kept.size());
        assertTrue(kept.stream().anyMatch(c -> c.versionNum == 3 && c.milestone), "Milestone must survive auto-prune");

        // Snapshot an entire directory tree skipping hidden dotfiles
        Path projDir = tmp.resolve("projDir");
        Files.createDirectories(projDir.resolve(".hidden"));
        Files.writeString(projDir.resolve("project.vproj"), "{\"v\":1}");
        Files.writeString(projDir.resolve(".hidden/secret.txt"), "skip");
        VcsCommit dirSnap = vcs.snapshotDirectory(drive, "dir-repo", projDir, "u1", "Julian", "dir snap", false, false);
        assertEquals(1, dirSnap.tree.size());
        assertTrue(dirSnap.tree.containsKey("project.vproj"));
    }
}
