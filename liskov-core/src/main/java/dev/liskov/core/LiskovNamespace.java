package dev.liskov.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Describes a mod namespace registered in Liskov (e.g. {@code schematics}, {@code manhattan},
 * {@code vitruvius}, {@code heavymatica}).
 */
public record LiskovNamespace(
        String id,
        String displayName,
        Path rootDir,
        SemanticColor accentRole,
        String badge,
        List<String> extensions
) {
    public LiskovNamespace {
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }
}
