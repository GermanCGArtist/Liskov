package dev.liskov.core.vcs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks the active branch, branch tip commit IDs, and monotonically increasing version counter ({@code v1}, {@code v2}...)
 * for one repository inside {@code .liskov-vcs/repos/<driveId>/<repoId>/head.json}.
 */
public final class VcsHead {
    public String repoId;
    public String driveId;
    public String activeBranch = "main";
    public int latestVersion = 0;
    public String headCommitId = "";
    public Map<String, String> branchTips = new LinkedHashMap<>();
}
