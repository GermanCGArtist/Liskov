package dev.liskov.core.acl;

/**
 * Lock metadata attached to a project, shared folder, or repository item.
 */
public final class LockState {
    public boolean locked = false;
    public String authorUuid = "";
    public String authorName = "";
    public String lockedByUuid = "";
    public String lockedByName = "";
    public String lockReason = "";
    public long lockedAtMs = 0L;
}
