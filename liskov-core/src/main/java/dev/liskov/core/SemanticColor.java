package dev.liskov.core;

/**
 * Semantic color roles matching the ModsUndBots terminal palette.
 * UI layers map each role to the active theme preset's concrete RGB color.
 */
public enum SemanticColor {
    FOCUS("focus"),
    CTA("cta"),
    BOT("bot"),
    PLAYER("player"),
    MR("mr"),
    CF("cf"),
    WARN("warn"),
    META("meta"),
    DANGER("danger"),
    INK_MUTED("ink_muted");

    private final String id;

    SemanticColor(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static SemanticColor fromId(String id) {
        if (id == null) return FOCUS;
        String norm = id.trim().toLowerCase();
        for (SemanticColor c : values()) {
            if (c.id.equals(norm)) return c;
        }
        return FOCUS;
    }
}
