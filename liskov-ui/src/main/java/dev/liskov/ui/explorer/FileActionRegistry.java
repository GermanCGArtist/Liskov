package dev.liskov.ui.explorer;

import dev.liskov.core.CompletionTag;
import dev.liskov.core.LiskovFileType;
import dev.liskov.core.SemanticColor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Registry allowing sibling mods (Manhattan, Vitruvius, Heavymatica) to attach custom Inspector
 * actions and live {@link CompletionTag} providers for any file type.
 */
public final class FileActionRegistry {
    public record Action(
            String id,
            String label,
            SemanticColor tone,
            Predicate<LiskovFileType> supports,
            Consumer<Path> onClick
    ) {}

    public interface StatusProvider {
        CompletionTag statusOf(Path file, LiskovFileType type);
    }

    private static final List<Action> ACTIONS = new ArrayList<>();
    private static final List<StatusProvider> STATUS_PROVIDERS = new ArrayList<>();

    private FileActionRegistry() {}

    public static synchronized void registerAction(Action action) {
        if (action == null) return;
        ACTIONS.removeIf(a -> a.id().equals(action.id()));
        ACTIONS.add(action);
    }

    public static synchronized void registerStatusProvider(StatusProvider provider) {
        if (provider != null && !STATUS_PROVIDERS.contains(provider)) {
            STATUS_PROVIDERS.add(provider);
        }
    }

    public static synchronized List<Action> actionsFor(LiskovFileType type) {
        List<Action> out = new ArrayList<>();
        for (Action a : ACTIONS) {
            if (a.supports() == null || a.supports().test(type)) out.add(a);
        }
        return out;
    }

    public static synchronized CompletionTag statusOf(Path file, LiskovFileType type) {
        for (StatusProvider sp : STATUS_PROVIDERS) {
            CompletionTag tag = sp.statusOf(file, type);
            if (tag != null) return tag;
        }
        return CompletionTag.NOT_STARTED;
    }
}
