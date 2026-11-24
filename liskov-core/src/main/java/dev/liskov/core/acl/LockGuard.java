package dev.liskov.core.acl;

/**
 * Author / OP ({@code "UNLOCK"}) authorization rules for locked projects and shared folders.
 */
public final class LockGuard {
    public static final String OP_CONFIRM_TOKEN = "UNLOCK";

    public record Decision(boolean allowed, String reason) {}

    private LockGuard() {}

    /**
     * Author ({@code actorUuid.equals(authorUuid)} or locker) unlocks directly.
     * Non-author OP is allowed <b>only</b> when {@code isOp && "UNLOCK".equals(confirmText)}.
     * Otherwise rejected.
     */
    public static Decision canUnlock(LockState lock, String actorUuid, boolean isOp, String confirmText) {
        if (lock == null || !lock.locked) {
            return new Decision(true, "Not locked.");
        }
        if (actorUuid != null && !actorUuid.isBlank()) {
            if (actorUuid.equals(lock.authorUuid) || actorUuid.equals(lock.lockedByUuid)) {
                return new Decision(true, "Author unlock authorized.");
            }
        }
        if (isOp) {
            if (OP_CONFIRM_TOKEN.equals(confirmText)) {
                return new Decision(true, "OP override unlock authorized.");
            }
            return new Decision(false, "OP override requires exact confirmation text \"" + OP_CONFIRM_TOKEN + "\".");
        }
        return new Decision(false, "Only the author (" + lock.authorName + ") or an OP typing UNLOCK can unlock this item.");
    }

    public static boolean canModify(LockState lock, String actorUuid) {
        if (lock == null || !lock.locked) return true;
        return actorUuid != null && !actorUuid.isBlank()
                && (actorUuid.equals(lock.authorUuid) || actorUuid.equals(lock.lockedByUuid));
    }
}
