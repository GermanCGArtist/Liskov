package dev.liskov.core;

/**
 * Generic completion and lock status tags rendered by {@code LiskovExplorer} and mod Queue views.
 */
public enum CompletionTag {
    LOCKED("LOCKED", SemanticColor.DANGER),
    ERROR("ERROR", SemanticColor.DANGER),
    IN_PROGRESS("IN PROGRESS", SemanticColor.MR),
    PAUSED("PAUSED", SemanticColor.WARN),
    DONE("DONE", SemanticColor.PLAYER),
    NOT_STARTED("IDLE", SemanticColor.INK_MUTED);

    private final String label;
    private final SemanticColor colorRole;

    CompletionTag(String label, SemanticColor colorRole) {
        this.label = label;
        this.colorRole = colorRole;
    }

    public String label() {
        return label;
    }

    public SemanticColor colorRole() {
        return colorRole;
    }

    public static CompletionTag deriveProjectTag(
            boolean locked,
            boolean hasError,
            boolean active,
            boolean paused,
            int doneChunks,
            int totalChunks
    ) {
        if (locked) return LOCKED;
        if (hasError) return ERROR;
        if (totalChunks > 0 && doneChunks >= totalChunks) return DONE;
        if (active && doneChunks < totalChunks) return IN_PROGRESS;
        if (paused) return PAUSED;
        if (doneChunks > 0) return IN_PROGRESS;
        return NOT_STARTED;
    }
}
