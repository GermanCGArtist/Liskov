package dev.liskov.core.acl;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Named ACL policy (covering a shared folder or repository), replacing {@code VitruviusAccess}'s {@code AclGroup}.
 */
public final class AclGroup {
    public String id;
    public String ownerUuid = "";
    public String ownerName = "";
    public AclRole defaultRole = AclRole.VIEW;
    /** Player UUID or "*" -> AclRole */
    public Map<String, AclRole> memberRoles = new LinkedHashMap<>();
    /** Named group tags (e.g. "builders") -> AclRole */
    public Map<String, AclRole> groupRoles = new LinkedHashMap<>();
    /** Optional player names for UI display (UUID -> name). */
    public Map<String, String> playerNames = new LinkedHashMap<>();

    public static final class NamedTeam {
        public String name;
        public Set<String> memberUuids = new LinkedHashSet<>();
    }
}
