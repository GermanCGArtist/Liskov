package dev.liskov.core.sync;

import dev.liskov.core.acl.LockState;
import dev.liskov.core.vcs.VcsCommit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire protocol constants and JSON DTOs for {@code liskov:sync} shared between server and client.
 */
public final class SyncWire {
    public static final String CHANNEL_NAMESPACE = "liskov";
    public static final String CHANNEL_PATH = "sync";
    public static final int PROTOCOL_VERSION = 1;
    public static final int CHUNK_SIZE = 64 * 1024;
    public static final int MAX_JSON_BYTES = 512 * 1024;
    public static final int MAX_CHUNK_BYTES = 128 * 1024;

    // S2C message types
    public static final String HELLO = "HELLO";
    public static final String TREE_SNAPSHOT = "TREE_SNAPSHOT";
    public static final String NEED_BLOBS = "NEED_BLOBS";
    public static final String BLOB_CHUNK = "BLOB_CHUNK";
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
