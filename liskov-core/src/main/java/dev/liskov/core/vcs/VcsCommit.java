package dev.liskov.core.vcs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One immutable snapshot inside hidden {@code .liskov-vcs/}.
 * Stores a map of {@code relativePath -> sha256} so unchanged files across hundreds of saves share one blob.
 */
public final class VcsCommit {
    public String commitId;
    public int versionNum;
    public List<String> parentIds = new ArrayList<>();
    public String branch = "main";
    public String authorUuid = "";
    public String authorName = "";
    public long timestampMs;
    public String message = "";
    public boolean milestone = false;
    public boolean autoSave = false;
    public long deltaBytes = 0L;
    /** Map from relative path inside the item/project bundle (e.g. "project.vproj", "files/tower.litematic") to SHA-256. */
    public Map<String, String> tree = new LinkedHashMap<>();

    public String shortId() {
        return commitId != null && commitId.length() > 7 ? commitId.substring(0, 7) : String.valueOf(commitId);
    }
}
