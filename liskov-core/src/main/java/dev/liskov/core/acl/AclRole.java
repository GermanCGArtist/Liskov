package dev.liskov.core.acl;

/**
 * Permission levels for Liskov server repositories and shared folders.
 */
public enum AclRole {
    NONE(0),
    VIEW(1),
    EDIT(2),
    ADMIN(3);

    private final int level;

    AclRole(int level) {
        this.level = level;
    }

    public boolean allows(AclRole required) {
        return required == null || this.level >= required.level;
    }
}
