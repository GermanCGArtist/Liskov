package dev.liskov.core.sync;

import dev.liskov.core.acl.LockState;
import dev.liskov.core.vcs.VcsCommit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Wire protocol constants and JSON DTOs for {@code liskov:sync} shared between server and client.
 */
public final class SyncWire {
    public static final String CHANNEL_NAMESPACE = "liskov";
    public static final String CHANNEL_PATH = "sync";
    public static final int PROTOCOL_VERSION = 1;
    /**
     * Binary chunk size kept safely below Minecraft's 32,767-byte C2S {@code ServerboundCustomPayloadPacket} limit
     * even after adding the JSON chunk metadata envelope and channel framing.
     */
    public static final int CHUNK_SIZE = 24 * 1024;
    /** JSON envelopes larger than this (UTF-8 bytes) are gzip-compressed and split into {@link #JSON_CHUNK} frames. */
    public static final int MAX_SINGLE_JSON_BYTES = 24 * 1024;
    public static final int MAX_JSON_BYTES = 512 * 1024;
    public static final int MAX_CHUNK_BYTES = 128 * 1024;
    /** Maximum allowed decompressed payload size (64 MiB) to guard against malformed/zip-bomb packets. */
    public static final int MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024;

    // S2C message types
    public static final String HELLO = "HELLO";
    public static final String TREE_SNAPSHOT = "TREE_SNAPSHOT";
    public static final String NEED_BLOBS = "NEED_BLOBS";
    public static final String BLOB_CHUNK = "BLOB_CHUNK";
    public static final String JSON_CHUNK = "JSON_CHUNK";
    public static final String PUSH_ACK = "PUSH_ACK";
    public static final String REPO_HEAD_UPDATED = "REPO_HEAD_UPDATED";
    public static final String REPO_DELETED = "REPO_DELETED";
    public static final String HISTORY_RESPONSE = "HISTORY_RESPONSE";
    public static final String ERROR = "ERROR";

    // C2S message types
    public static final String PUSH_CHECK = "PUSH_CHECK";
    public static final String PUSH_COMMIT = "PUSH_COMMIT";
    public static final String PULL_REQUEST = "PULL_REQUEST";
    public static final String HISTORY_REQUEST = "HISTORY_REQUEST";
    public static final String LOCK_MUTATE = "LOCK_MUTATE";
    public static final String ACL_MUTATE = "ACL_MUTATE";
    public static final String DELETE_REPO = "DELETE_REPO";
    public static final String RENAME_REPO = "RENAME_REPO";
    public static final String FOLDER_MUTATE = "FOLDER_MUTATE";

    private SyncWire() {}

    /** Result of {@link #compressIfSmaller(byte[])}. */
    public record CompressedPayload(byte[] bytes, boolean compressed) {}

    /**
     * Gzip-compresses {@code raw} and returns the compressed bytes only when strictly smaller than {@code raw}.
     * Already-compressed formats (such as {@code .litematic}) are returned as-is without inflation.
     */
    public static CompressedPayload compressIfSmaller(byte[] raw) throws IOException {
        byte[] src = raw == null ? new byte[0] : raw;
        if (src.length < 64) {
            return new CompressedPayload(src, false);
        }
        byte[] gz = gzip(src);
        if (gz.length < src.length) {
            return new CompressedPayload(gz, true);
        }
        return new CompressedPayload(src, false);
    }

    /** Gzip-compresses {@code raw} bytes. */
    public static byte[] gzip(byte[] raw) throws IOException {
        byte[] src = raw == null ? new byte[0] : raw;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(src.length, 4096));
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(src);
        }
        return baos.toByteArray();
    }

    /** Decompresses gzip bytes with a hard {@code maxBytes} ceiling. */
    public static byte[] gunzip(byte[] compressed, int maxBytes) throws IOException {
        if (compressed == null || compressed.length == 0) return new byte[0];
        int limit = Math.max(1, maxBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            int n;
            while ((n = gis.read(buf)) >= 0) {
                total += n;
                if (total > limit) {
                    throw new IOException("Decompressed payload exceeds limit of " + limit + " bytes");
                }
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    /** Splits {@code data} into chunks of at most {@code chunkSize} bytes (always at least one chunk). */
    public static List<byte[]> splitChunks(byte[] data, int chunkSize) {
        byte[] src = data == null ? new byte[0] : data;
        int size = Math.max(1, chunkSize);
        int total = Math.max(1, (src.length + size - 1) / size);
        List<byte[]> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int start = i * size;
            int end = Math.min(src.length, start + size);
            byte[] slice = new byte[end - start];
            if (slice.length > 0) System.arraycopy(src, start, slice, 0, slice.length);
            out.add(slice);
        }
        return out;
    }

    public static final class FolderSummary {
        public String folderId;
        public String name;
        public String colorRole = "focus";
        public String ownerUuid = "";
        public String ownerName = "";
        public boolean archived;
        public LockState lock = new LockState();
        public List<RepoEntry> entries = new ArrayList<>();
    }

    public static final class RepoEntry {
        public String repoId;
        public String folderId;
        public String relativePath;
        public String fileTypeId;
        public int versionNum;
        public String headCommitId;
        public String authorUuid = "";
        public String authorName = "";
        public long updatedAtMs;
        public long sizeBytes;
        public LockState lock = new LockState();
        public Map<String, String> tree = new LinkedHashMap<>();
    }

    public static final class HelloBody {
        public int protocolVersion = PROTOCOL_VERSION;
        public String serverId;
        public String displayName;
        public List<FolderSummary> folders = new ArrayList<>();
    }

    public static final class PushCheckBody {
        public String folderId;
        public String relativePath;
        public String fileTypeId;
        public VcsCommit commit;
        public List<String> requiredHashes = new ArrayList<>();
    }

    public static final class NeedBlobsBody {
        public String repoId;
        public String commitId;
        public List<String> missingHashes = new ArrayList<>();
    }

    public static final class BlobChunkMeta {
        public String transferId;
        public String sha256;
        public int chunkIndex;
        public int totalChunks;
        public long totalBytes;
        public boolean compressed;
    }

    public static final class JsonChunkMeta {
        public String transferId;
        public int chunkIndex;
        public int totalChunks;
        public int totalBytes;
        public boolean compressed;
    }

    public static final class RepoUpdatedBody {
        public String serverId;
        public RepoEntry entry;
        public VcsCommit commit;
    }

    public static final class PullRequestBody {
        public String repoId;
        public String commitId;
        public List<String> missingHashes = new ArrayList<>();
    }

    public static final class LockMutateBody {
        public String folderId;
        public String repoId;
        public boolean locked;
        public String reason = "";
        public String confirmUnlockText = "";
    }

    public static final class AclMutateBody {
        public String targetId;
        public String subjectUuid;
        public String subjectName = "";
        public boolean isTeam;
        public String role;
    }

    public static final class RepoDeleteBody {
        public String folderId;
        public String repoId;
    }

    public static final class RepoRenameBody {
        public String folderId;
        public String repoId;
        public String newRelativePath;
    }

    public static final class RepoDeletedBody {
        public String serverId;
        public String folderId;
        public String repoId;
        public String relativePath;
    }

    public static final class FolderMutateBody {
        /** CREATE | RENAME | ARCHIVE | UNARCHIVE | DELETE */
        public String action;
        public String folderId;
        public String name;
        public String colorRole = "focus";
    }

    public static final class HistoryResponseBody {
        public String repoId;
        public List<VcsCommit> commits = new ArrayList<>();
    }
}
