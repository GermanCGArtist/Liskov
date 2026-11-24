package dev.liskov.core.vcs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.liskov.core.LiskovPaths;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Hidden content-addressed Version Control store ({@code <liskovRoot>/.liskov-vcs/}).
 *
 * <ul>
 *   <li>All blobs are gzip-compressed and stored once by SHA-256 under {@code objects/<sha256>}.</li>
 *   <li>500 saves of a project with unchanged {@code .litematic} payloads store those payloads exactly once.</li>
 *   <li>Explorer shows only the single live file/project with a compact {@code vN} badge.</li>
 *   <li>Supports linear history, Milestones, read-only commit materialization (for 3D previewing old saves),
 *       non-destructive {@link #restore}, optional branching/3-way merge, and {@link #prune}.</li>
 * </ul>
 */
public final class VcsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type COMMIT_LIST_TYPE = new TypeToken<List<VcsCommit>>() {}.getType();

    private final Path vcsRoot;
    private final Path objectsDir;
    private final Path reposDir;

    public VcsStore(Path vcsRoot) throws IOException {
        this.vcsRoot = vcsRoot.toAbsolutePath().normalize();
        this.objectsDir = this.vcsRoot.resolve("objects");
        this.reposDir = this.vcsRoot.resolve("repos");
        Files.createDirectories(this.objectsDir);
        Files.createDirectories(this.reposDir);
    }

    public Path objectsDir() {
        return objectsDir;
    }

    public Path repoDir(String driveId, String repoId) {
        return reposDir.resolve(LiskovPaths.safeName(driveId)).resolve(LiskovPaths.safeName(repoId));
    }

    public static String repoIdFor(String relativePath) {
        return LiskovPaths.sha256Hex(relativePath == null ? "" : relativePath.replace('\\', '/')).substring(0, 16);
    }

    // ------------------------------------------------------------------ content-addressed blobs

    public boolean hasBlob(String sha256) {
        return sha256 != null && Files.isRegularFile(objectsDir.resolve(LiskovPaths.safeName(sha256)));
    }

    /** Stores {@code rawBytes} as a gzip-compressed blob if unseen; returns its SHA-256 hex digest. */
    public String putBlob(byte[] rawBytes) throws IOException {
        byte[] data = rawBytes == null ? new byte[0] : rawBytes;
        String sha = LiskovPaths.sha256Hex(data);
        Path obj = objectsDir.resolve(sha);
        if (!Files.isRegularFile(obj)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
                gz.write(data);
            }
            LiskovPaths.writeAtomicBytes(obj, baos.toByteArray());
        }
        return sha;
    }

    /** Reads and decompresses a stored blob by SHA-256, or null if missing. */
    public byte[] getBlob(String sha256) throws IOException {
        if (sha256 == null) return null;
        Path obj = objectsDir.resolve(LiskovPaths.safeName(sha256));
        if (!Files.isRegularFile(obj)) return null;
        byte[] gz = Files.readAllBytes(obj);
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return gis.readAllBytes();
        }
    }

    public List<String> missingBlobs(Iterable<String> requiredHashes) {
        List<String> missing = new ArrayList<>();
        if (requiredHashes == null) return missing;
        for (String h : requiredHashes) {
            if (h != null && !hasBlob(h) && !missing.contains(h)) missing.add(h);
        }
        return missing;
    }

    // ------------------------------------------------------------------ head & commit DAG

    public synchronized VcsHead loadHead(String driveId, String repoId) {
        Path f = repoDir(driveId, repoId).resolve("head.json");
        if (Files.isRegularFile(f)) {
            try {
                VcsHead h = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), VcsHead.class);
                if (h != null) {
                    if (h.branchTips == null) h.branchTips = new LinkedHashMap<>();
                    if (h.activeBranch == null || h.activeBranch.isBlank()) h.activeBranch = "main";
                    return h;
                }
            } catch (Exception ignored) {
            }
        }
        VcsHead h = new VcsHead();
        h.driveId = driveId;
        h.repoId = repoId;
        return h;
    }

    public synchronized List<VcsCommit> loadCommits(String driveId, String repoId) {
        Path f = repoDir(driveId, repoId).resolve("commits.json");
        if (Files.isRegularFile(f)) {
            try {
                List<VcsCommit> list = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), COMMIT_LIST_TYPE);
                if (list != null) return list;
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>();
    }

    private void saveRepoState(String driveId, String repoId, VcsHead head, List<VcsCommit> commits) throws IOException {
        Path dir = repoDir(driveId, repoId);
        Files.createDirectories(dir);
        LiskovPaths.writeAtomicUtf8(dir.resolve("head.json"), GSON.toJson(head));
        LiskovPaths.writeAtomicUtf8(dir.resolve("commits.json"), GSON.toJson(commits));
    }

    // ------------------------------------------------------------------ snapshot / read / restore

    /**
     * Records a new snapshot of {@code files} (relativePath -> raw bytes) on the active branch.
     * Unchanged files reuse existing SHA-256 blobs; {@code deltaBytes} counts only newly written blobs.
     */
    public synchronized VcsCommit snapshot(
            String driveId,
            String repoId,
            String authorUuid,
            String authorName,
            String message,
            boolean milestone,
            boolean autoSave,
            Map<String, byte[]> files
    ) throws IOException {
        VcsHead head = loadHead(driveId, repoId);
        List<VcsCommit> commits = loadCommits(driveId, repoId);
        String branch = head.activeBranch;
        String parent = head.branchTips.get(branch);

        Map<String, String> tree = new LinkedHashMap<>();
        long delta = 0L;
        for (var entry : files.entrySet()) {
            String rel = entry.getKey().replace('\\', '/');
            byte[] data = entry.getValue() == null ? new byte[0] : entry.getValue();
            String sha = LiskovPaths.sha256Hex(data);
            if (!hasBlob(sha)) {
                delta += data.length;
                putBlob(data);
            }
            tree.put(rel, sha);
        }

        VcsCommit c = new VcsCommit();
        c.versionNum = head.latestVersion + 1;
        c.branch = branch;
        if (parent != null && !parent.isBlank()) c.parentIds.add(parent);
        c.authorUuid = authorUuid == null ? "" : authorUuid;
        c.authorName = authorName == null ? "" : authorName;
        c.timestampMs = System.currentTimeMillis();
        c.message = (message == null || message.isBlank()) ? ("Saved v" + c.versionNum) : message.strip();
        c.milestone = milestone;
        c.autoSave = autoSave;
        c.deltaBytes = delta;
        c.tree = tree;
        c.commitId = LiskovPaths.sha256Hex(c.versionNum + "|" + c.branch + "|" + c.timestampMs + "|" + GSON.toJson(tree)).substring(0, 12);

        commits.add(0, c);
        head.latestVersion = c.versionNum;
        head.headCommitId = c.commitId;
        head.branchTips.put(branch, c.commitId);
        saveRepoState(driveId, repoId, head, commits);
        return c;
    }

    /**
     * Imports a commit pushed from a peer/server when all referenced blobs are already present locally.
     */
    public synchronized void acceptRemoteCommit(String driveId, String repoId, VcsCommit commit, boolean makeActiveHead) throws IOException {
        VcsHead head = loadHead(driveId, repoId);
        List<VcsCommit> commits = loadCommits(driveId, repoId);
        boolean exists = commits.stream().anyMatch(x -> x.commitId.equals(commit.commitId));
        if (!exists) {
            commits.add(0, commit);
        }
        if (commit.versionNum > head.latestVersion) head.latestVersion = commit.versionNum;
        head.branchTips.put(commit.branch, commit.commitId);
        if (makeActiveHead) {
            head.activeBranch = commit.branch;
            head.headCommitId = commit.commitId;
        }
        saveRepoState(driveId, repoId, head, commits);
    }

    /**
     * Materializes an older commit's files purely in memory (read-only) so the UI can 3D-preview historical versions
     * without touching the workspace on disk.
     */
    public synchronized Map<String, byte[]> readCommitFiles(String driveId, String repoId, String commitId) throws IOException {
        VcsCommit target = findCommit(loadCommits(driveId, repoId), commitId);
        if (target == null) return Map.of();
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (var e : target.tree.entrySet()) {
            byte[] data = getBlob(e.getValue());
            if (data != null) out.put(e.getKey(), data);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Non-destructively restores {@code targetCommitId} onto the active branch by appending a new tip commit
     * ({@code vN+1: Restored from vK}) and returning the materialized bytes to write to disk.
     */
    public synchronized RestoreOutput restore(
            String driveId,
            String repoId,
            String targetCommitId,
            String actorUuid,
            String actorName
    ) throws IOException {
        List<VcsCommit> commits = loadCommits(driveId, repoId);
        VcsCommit target = findCommit(commits, targetCommitId);
        if (target == null) {
            throw new IllegalArgumentException("Commit not found: " + targetCommitId);
        }
        Map<String, byte[]> files = readCommitFiles(driveId, repoId, targetCommitId);
        VcsCommit restored = snapshot(
                driveId, repoId, actorUuid, actorName,
                "Restored from v" + target.versionNum + " (#" + target.shortId() + ")",
                false, false, files
        );
        return new RestoreOutput(restored, files);
    }

    public record RestoreOutput(VcsCommit commit, Map<String, byte[]> files) {}

    public synchronized boolean setMilestone(String driveId, String repoId, String commitId, boolean milestone) throws IOException {
        VcsHead head = loadHead(driveId, repoId);
        List<VcsCommit> commits = loadCommits(driveId, repoId);
        VcsCommit c = findCommit(commits, commitId);
        if (c == null) return false;
        c.milestone = milestone;
        saveRepoState(driveId, repoId, head, commits);
        return true;
    }

    // ------------------------------------------------------------------ branching & 3-way merge

    public synchronized void createBranch(String driveId, String repoId, String newBranch) throws IOException {
        if (newBranch == null || newBranch.isBlank()) throw new IllegalArgumentException("Branch name required.");
        String clean = newBranch.strip().replace(' ', '_');
        VcsHead head = loadHead(driveId, repoId);
        List<VcsCommit> commits = loadCommits(driveId, repoId);
        if (head.branchTips.containsKey(clean)) {
            throw new IllegalArgumentException("Branch already exists: " + clean);
        }
        String baseTip = head.headCommitId;
        if (baseTip != null && !baseTip.isBlank()) {
            head.branchTips.put(clean, baseTip);
        }
        head.activeBranch = clean;
        saveRepoState(driveId, repoId, head, commits);
    }

    public synchronized Map<String, byte[]> switchBranch(String driveId, String repoId, String branch) throws IOException {
        VcsHead head = loadHead(driveId, repoId);
        List<VcsCommit> commits = loadCommits(driveId, repoId);
        String tip = head.branchTips.get(branch);
        if (tip == null) throw new IllegalArgumentException("Unknown branch: " + branch);
        head.activeBranch = branch;
        head.headCommitId = tip;
        saveRepoState(driveId, repoId, head, commits);
        return readCommitFiles(driveId, repoId, tip);
    }

    /**
     * Performs a 3-way merge of {@code sourceBranch} into the active branch, detecting file-level conflicts
     * relative to their lowest common ancestor. On conflicts, keeps {@code sourceBranch} version or active version
     * deterministically while reporting every {@code conflictedPath}.
     */
    public synchronized VcsMergeResult mergeBranch(
            String driveId,
            String repoId,
            String sourceBranch,
            String actorUuid,
            String actorName
    ) throws IOException {
        VcsHead head = loadHead(driveId, repoId);
        List<VcsCommit> commits = loadCommits(driveId, repoId);
        String intoBranch = head.activeBranch;
        if (Objects.equals(intoBranch, sourceBranch)) {
            return new VcsMergeResult(VcsMergeResult.Status.UP_TO_DATE, findCommit(commits, head.headCommitId), List.of());
        }
        String intoId = head.branchTips.get(intoBranch);
        String srcId = head.branchTips.get(sourceBranch);
        if (srcId == null) throw new IllegalArgumentException("Unknown source branch: " + sourceBranch);

        VcsCommit intoC = findCommit(commits, intoId);
        VcsCommit srcC = findCommit(commits, srcId);
        if (intoC == null) {
            head.branchTips.put(intoBranch, srcId);
            head.headCommitId = srcId;
            saveRepoState(driveId, repoId, head, commits);
            return new VcsMergeResult(VcsMergeResult.Status.FAST_FORWARD, srcC, List.of());
        }
        if (isAncestor(commits, srcId, intoId)) {
            return new VcsMergeResult(VcsMergeResult.Status.UP_TO_DATE, intoC, List.of());
        }
        if (isAncestor(commits, intoId, srcId)) {
            head.branchTips.put(intoBranch, srcId);
            head.headCommitId = srcId;
            saveRepoState(driveId, repoId, head, commits);
            return new VcsMergeResult(VcsMergeResult.Status.FAST_FORWARD, srcC, List.of());
        }

        VcsCommit lca = findLca(commits, intoId, srcId);
        Map<String, String> baseTree = lca != null ? lca.tree : Map.of();
        Map<String, String> mergedTree = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();

        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(baseTree.keySet());
        allPaths.addAll(intoC.tree.keySet());
        allPaths.addAll(srcC.tree.keySet());

        for (String p : allPaths) {
            String b = baseTree.get(p);
            String o = intoC.tree.get(p);
            String t = srcC.tree.get(p);
            if (Objects.equals(o, t)) {
                if (o != null) mergedTree.put(p, o);
            } else if (Objects.equals(o, b)) {
                if (t != null) mergedTree.put(p, t);
            } else if (Objects.equals(t, b)) {
                if (o != null) mergedTree.put(p, o);
            } else {
                // Both sides modified differently; keep 'into' side and record conflict
                if (o != null) mergedTree.put(p, o);
                else if (t != null) mergedTree.put(p, t);
                conflicts.add(p);
            }
        }

        VcsCommit mergeCommit = new VcsCommit();
        mergeCommit.versionNum = head.latestVersion + 1;
        mergeCommit.branch = intoBranch;
        mergeCommit.parentIds.add(intoId);
        mergeCommit.parentIds.add(srcId);
        mergeCommit.authorUuid = actorUuid == null ? "" : actorUuid;
        mergeCommit.authorName = actorName == null ? "" : actorName;
        mergeCommit.timestampMs = System.currentTimeMillis();
        mergeCommit.message = "Merge " + sourceBranch + " into " + intoBranch
                + (conflicts.isEmpty() ? "" : " (" + conflicts.size() + " conflict(s))");
        mergeCommit.tree = mergedTree;
        mergeCommit.commitId = LiskovPaths.sha256Hex(mergeCommit.versionNum + "|" + intoId + "|" + srcId + "|" + mergeCommit.timestampMs).substring(0, 12);

        commits.add(0, mergeCommit);
        head.latestVersion = mergeCommit.versionNum;
        head.headCommitId = mergeCommit.commitId;
        head.branchTips.put(intoBranch, mergeCommit.commitId);
        saveRepoState(driveId, repoId, head, commits);

        return new VcsMergeResult(
                conflicts.isEmpty() ? VcsMergeResult.Status.MERGED_CLEANLY : VcsMergeResult.Status.MERGED_WITH_CONFLICTS,
                mergeCommit,
                List.copyOf(conflicts)
        );
    }

    // ------------------------------------------------------------------ retention & GC

    /**
     * Prunes old non-milestone commits older than {@code maxAgeDays} while ALWAYS keeping:
     * 1) the most recent {@code keepRecentCount} commits, 2) every Milestone ({@code milestone == true}),
     * and 3) every branch tip commit.
     */
    public synchronized int prune(String driveId, String repoId, int maxAgeDays, int keepRecentCount) throws IOException {
        VcsHead head = loadHead(driveId, repoId);
        List<VcsCommit> commits = loadCommits(driveId, repoId);
        long cutoffMs = System.currentTimeMillis() - Math.max(0L, maxAgeDays) * 86_400_000L;
        Set<String> protectedIds = new HashSet<>(head.branchTips.values());
        if (head.headCommitId != null) protectedIds.add(head.headCommitId);

        List<VcsCommit> kept = new ArrayList<>();
        int removed = 0;
        for (int i = 0; i < commits.size(); i++) {
            VcsCommit c = commits.get(i);
            boolean keep = i < keepRecentCount || c.milestone || protectedIds.contains(c.commitId) || c.timestampMs >= cutoffMs;
            if (keep) kept.add(c);
            else removed++;
        }
        if (removed > 0) {
            saveRepoState(driveId, repoId, head, kept);
        }
        return removed;
    }

    /** Garbage-collects unreferenced blobs across all repos under {@code .liskov-vcs/repos/}. */
    public synchronized int gcUnreferencedObjects() throws IOException {
        Set<String> referenced = new HashSet<>();
        if (Files.isDirectory(reposDir)) {
            try (Stream<Path> walk = Files.walk(reposDir)) {
                for (Path p : walk.filter(f -> f.getFileName().toString().equals("commits.json")).toList()) {
                    try {
                        List<VcsCommit> commits = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), COMMIT_LIST_TYPE);
                        if (commits != null) {
                            for (VcsCommit c : commits) {
                                if (c.tree != null) referenced.addAll(c.tree.values());
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        int deleted = 0;
        if (Files.isDirectory(objectsDir)) {
            try (Stream<Path> objs = Files.list(objectsDir)) {
                for (Path o : objs.filter(Files::isRegularFile).toList()) {
                    if (!referenced.contains(o.getFileName().toString())) {
                        Files.deleteIfExists(o);
                        deleted++;
                    }
                }
            }
        }
        return deleted;
    }

    // ------------------------------------------------------------------ DAG helpers

    private static VcsCommit findCommit(List<VcsCommit> commits, String id) {
        if (id == null) return null;
        for (VcsCommit c : commits) {
            if (id.equals(c.commitId)) return c;
        }
        return null;
    }

    private static boolean isAncestor(List<VcsCommit> commits, String possibleAncestor, String start) {
        if (possibleAncestor == null || start == null) return false;
        Map<String, VcsCommit> map = new HashMap<>();
        for (VcsCommit c : commits) map.put(c.commitId, c);
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            String cur = q.poll();
            if (cur.equals(possibleAncestor)) return true;
            if (!seen.add(cur)) continue;
            VcsCommit c = map.get(cur);
            if (c != null && c.parentIds != null) q.addAll(c.parentIds);
        }
        return false;
    }

    private static VcsCommit findLca(List<VcsCommit> commits, String a, String b) {
        Map<String, VcsCommit> map = new HashMap<>();
        for (VcsCommit c : commits) map.put(c.commitId, c);
        Set<String> ancA = new HashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        if (a != null) q.add(a);
        while (!q.isEmpty()) {
            String cur = q.poll();
            if (!ancA.add(cur)) continue;
            VcsCommit c = map.get(cur);
            if (c != null && c.parentIds != null) q.addAll(c.parentIds);
        }
        ArrayDeque<String> qb = new ArrayDeque<>();
        Set<String> seenB = new HashSet<>();
        if (b != null) qb.add(b);
        while (!qb.isEmpty()) {
            String cur = qb.poll();
            if (!seenB.add(cur)) continue;
            if (ancA.contains(cur)) return map.get(cur);
            VcsCommit c = map.get(cur);
            if (c != null && c.parentIds != null) qb.addAll(c.parentIds);
        }
        return null;
    }
}
