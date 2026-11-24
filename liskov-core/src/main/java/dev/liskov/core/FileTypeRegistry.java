package dev.liskov.core;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of known Minecraft/mod file types, extensions, and 2-letter monogram badges.
 */
public final class FileTypeRegistry {
    public static final LiskovFileType LITEMATIC = new LiskovFileType(
            "litematic", ".litematic", List.of(), "Lm", "Litematica Schematic",
            SemanticColor.PLAYER, LiskovFileType.PreviewKind.SCHEMATIC_3D);
    public static final LiskovFileType VPROJ = new LiskovFileType(
            "vproj", ".vproj", List.of("project.json"), "Vp", "Vitruvius 3D Project",
            SemanticColor.FOCUS, LiskovFileType.PreviewKind.SCHEMATIC_3D);
    public static final LiskovFileType LASM = new LiskovFileType(
            "lasm", ".lasm", List.of(), "La", "Vitruvius Assembly",
            SemanticColor.BOT, LiskovFileType.PreviewKind.SCHEMATIC_3D);
    public static final LiskovFileType MPROJ = new LiskovFileType(
            "mproj", ".mproj", List.of(), "Mp", "Manhattan Project",
            SemanticColor.MR, LiskovFileType.PreviewKind.TEXT_EDITABLE);
    public static final LiskovFileType MSCRIPT = new LiskovFileType(
            "mscript", ".mscript", List.of(), "Ms", "Manhattan Script",
            SemanticColor.WARN, LiskovFileType.PreviewKind.TEXT_EDITABLE);
    public static final LiskovFileType MCAP = new LiskovFileType(
            "mcap", ".mcap", List.of(), "Mc", "Capture / LLM Context",
            SemanticColor.META, LiskovFileType.PreviewKind.TEXT_EDITABLE);
    public static final LiskovFileType HEAVYMATIC = new LiskovFileType(
            "heavymatic", ".heavymatic", List.of(), "Hm", "Heavymatica Spec",
            SemanticColor.CF, LiskovFileType.PreviewKind.SCHEMATIC_3D);
    public static final LiskovFileType CONFIG = new LiskovFileType(
            "json", ".json", List.of(), "Cf", "JSON Config",
            SemanticColor.INK_MUTED, LiskovFileType.PreviewKind.TEXT_READONLY);
    public static final LiskovFileType LOG = new LiskovFileType(
            "log", ".log", List.of(".txt"), "Lg", "Log / Text",
            SemanticColor.INK_MUTED, LiskovFileType.PreviewKind.TEXT_READONLY);
    public static final LiskovFileType GENERIC = new LiskovFileType(
            "file", "", List.of(), "Fl", "File",
            SemanticColor.INK_MUTED, LiskovFileType.PreviewKind.NONE);

    private final Map<String, LiskovFileType> byId = new LinkedHashMap<>();

    public FileTypeRegistry() {
        register(LITEMATIC);
        register(VPROJ);
        register(LASM);
        register(MPROJ);
        register(MSCRIPT);
        register(MCAP);
        register(HEAVYMATIC);
        register(CONFIG);
        register(LOG);
    }

    public synchronized void register(LiskovFileType type) {
        if (type != null) byId.put(type.id(), type);
    }

    public synchronized Collection<LiskovFileType> all() {
        return Collections.unmodifiableCollection(List.copyOf(byId.values()));
    }

    public synchronized LiskovFileType byId(String id) {
        return byId.getOrDefault(id, GENERIC);
    }

    /**
     * Resolves the {@link LiskovFileType} for a path, honoring dedicated extensions as well as
     * legacy path contexts (e.g. {@code manhattan/projects/*.json} -> {@code Mp}).
     */
    public synchronized LiskovFileType detect(Path path) {
        if (path == null) return GENERIC;
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        String normPath = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);

        // Exact alias filename match (e.g. legacy Vitruvius project.json)
        for (LiskovFileType t : byId.values()) {
            for (String alias : t.aliases()) {
                if (!alias.startsWith(".") && fileName.equals(alias.toLowerCase(Locale.ROOT))) {
                    return t;
                }
            }
        }
        // Primary extension match (prefer custom types before generic .json/.txt)
        for (LiskovFileType t : byId.values()) {
            if (!t.primaryExtension().isEmpty() && fileName.endsWith(t.primaryExtension().toLowerCase(Locale.ROOT))) {
                if (t == CONFIG) break; // check legacy contextual rules before falling back to generic Config
                return t;
            }
        }
        // Legacy contextual fallbacks
        if (fileName.endsWith(".json") && normPath.contains("manhattan/projects/") && !fileName.startsWith("_")) {
            return MPROJ;
        }
        if (fileName.endsWith(".txt")) {
            if (normPath.contains("manhattan/scripts/") || normPath.startsWith("scripts/") || normPath.contains("/scripts/")) return MSCRIPT;
            if (normPath.contains("manhattan/capture/") || normPath.contains("manhattan-context/")) return MCAP;
        }
        if (fileName.endsWith(".json")) return CONFIG;
        for (LiskovFileType t : byId.values()) {
            for (String alias : t.aliases()) {
                if (alias.startsWith(".") && fileName.endsWith(alias.toLowerCase(Locale.ROOT))) {
                    return t;
                }
            }
        }
        return GENERIC;
    }
}
