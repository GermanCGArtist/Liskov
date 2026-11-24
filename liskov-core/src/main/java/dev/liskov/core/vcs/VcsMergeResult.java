package dev.liskov.core.vcs;

import java.util.List;

/**
 * Outcome of a 3-way branch merge inside {@link VcsStore}.
 */
public record VcsMergeResult(
        Status status,
        VcsCommit mergedCommit,
        List<String> conflictedPaths
) {
    public enum Status {
        UP_TO_DATE,
        FAST_FORWARD,
        MERGED_CLEANLY,
        MERGED_WITH_CONFLICTS
    }
}
