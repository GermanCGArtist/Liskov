package dev.liskov.core.acl;

import java.util.Set;

/**
 * Pure-Java evaluator resolving effective {@link AclRole} for a player on a target folder/repo.
 */
public final class AccessEvaluator {
    private AccessEvaluator() {}

    public static AclRole effectiveRole(AclConfig cfg, String targetId, String playerUuid, boolean isOp) {
        if (isOp) return AclRole.ADMIN;
        if (cfg == null) return AclRole.EDIT;
        AclGroup g = cfg.policyFor(targetId);
        if (g == null) return AclRole.EDIT;
        if (playerUuid != null && !playerUuid.isBlank() && playerUuid.equals(g.ownerUuid)) {
            return AclRole.ADMIN;
        }
        AclRole best = g.defaultRole != null ? g.defaultRole : AclRole.NONE;
        if (playerUuid != null && g.memberRoles != null) {
            AclRole direct = g.memberRoles.get(playerUuid);
            if (direct != null && direct.ordinal() > best.ordinal()) best = direct;
            AclRole wild = g.memberRoles.get("*");
            if (wild != null && wild.ordinal() > best.ordinal()) best = wild;
        }
        if (playerUuid != null && g.groupRoles != null && cfg.teams != null) {
            for (var entry : g.groupRoles.entrySet()) {
                Set<String> members = cfg.teams.get(entry.getKey());
                if (members != null && members.contains(playerUuid)) {
                    if (entry.getValue().ordinal() > best.ordinal()) best = entry.getValue();
                }
            }
        }
        return best;
    }

    public static boolean canView(AclConfig cfg, String targetId, String playerUuid, boolean isOp) {
        return effectiveRole(cfg, targetId, playerUuid, isOp).allows(AclRole.VIEW);
    }

    public static boolean canEdit(AclConfig cfg, String targetId, String playerUuid, boolean isOp) {
        return effectiveRole(cfg, targetId, playerUuid, isOp).allows(AclRole.EDIT);
    }

    public static boolean canAdmin(AclConfig cfg, String targetId, String playerUuid, boolean isOp) {
        return effectiveRole(cfg, targetId, playerUuid, isOp).allows(AclRole.ADMIN);
    }
}
