package dev.liskov.core;

import java.util.List;

/**
 * Describes a file type managed by Liskov, including its Adobe-style 2-letter monogram badge
 * ({@code Lm}, {@code Vp}, {@code La}, {@code Mp}, {@code Ms}, {@code Mc}, {@code Hm}, etc.).
 */
public record LiskovFileType(
        String id,
        String primaryExtension,
        List<String> aliases,
        String monogram,
        String displayName,
        SemanticColor colorRole,
        PreviewKind previewKind
) {
    public enum PreviewKind {
        NONE,
        SCHEMATIC_3D,
        TEXT_EDITABLE,
        TEXT_READONLY
    }
}
