package dev.liskov.net;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Event bus notifying mods (Vitruvius, Manhattan, Heavymatica) when a local or synced server project
 * saves or auto-reloads, or when a concurrent peer save triggers a non-destructive conflict.
 */
public final class LiskovReloadBus {
    public record ReloadNotice(String driveId, String relativePath, int versionNum, String authorName) {}

    public record ConflictNotice(String serverId, String repoId, String relativePath, int serverVersion, String authorName, String commitId) {}

    public interface Listener {
        void onReload(ReloadNotice notice);

        default void onConflict(ConflictNotice conflict) {}
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private LiskovReloadBus() {}

    public static void subscribe(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    public static void unsubscribe(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void fireReload(ReloadNotice notice) {
        if (notice == null) return;
        for (Listener l : LISTENERS) {
            try {
                l.onReload(notice);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void fireConflict(ConflictNotice conflict) {
        if (conflict == null) return;
        for (Listener l : LISTENERS) {
            try {
                l.onConflict(conflict);
            } catch (Throwable ignored) {
            }
        }
    }
}
