package dev.liskov.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Windows-safe 2-second timestamp + file-size polling watcher (zero OS directory handle locks).
 */
public final class ReloadWatcher {
    public static final long DEFAULT_POLL_MS = 2000L;

    public record ReloadEvent(String namespaceId, List<Path> changedFiles) {}

    private record WatchRoot(String namespaceId, Path directory) {}

    private record FileStamp(long modifiedMs, long size) {}

    private final List<WatchRoot> roots = new CopyOnWriteArrayList<>();
    private final List<Consumer<ReloadEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Map<Path, FileStamp> known = new LinkedHashMap<>();
    private final long pollIntervalMs;
    private volatile boolean enabled = true;
    private long lastPollMs;

    public ReloadWatcher() {
        this(DEFAULT_POLL_MS);
    }

    public ReloadWatcher(long pollIntervalMs) {
        this.pollIntervalMs = Math.max(100L, pollIntervalMs);
    }

    public void watch(String namespaceId, Path directory) {
        if (namespaceId == null || directory == null) return;
        Path norm = directory.toAbsolutePath().normalize();
        roots.removeIf(r -> r.namespaceId().equals(namespaceId) && r.directory().equals(norm));
        roots.add(new WatchRoot(namespaceId, norm));
        seedBaseline(norm);
    }

    public void addListener(Consumer<ReloadEvent> listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Consumer<ReloadEvent> listener) {
        listeners.remove(listener);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void pollIfDue(long nowMs) {
        if (!enabled) return;
        if (nowMs - lastPollMs < pollIntervalMs) return;
        lastPollMs = nowMs;
        reloadNow();
    }

    /** Forces an immediate disk scan and emits {@link ReloadEvent}s for any created/modified/deleted files. */
    public synchronized List<ReloadEvent> reloadNow() {
        lastPollMs = System.currentTimeMillis();
        List<ReloadEvent> events = new ArrayList<>();
        for (WatchRoot root : roots) {
            List<Path> changed = scanRoot(root.directory());
            if (!changed.isEmpty()) {
                ReloadEvent ev = new ReloadEvent(root.namespaceId(), List.copyOf(changed));
                events.add(ev);
                for (Consumer<ReloadEvent> l : listeners) {
                    l.accept(ev);
                }
            }
        }
        return events;
    }

    private void seedBaseline(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir, 6)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (isIgnored(p)) continue;
                known.put(p, stampOf(p));
            }
        } catch (IOException ignored) {
        }
    }

    private List<Path> scanRoot(Path dir) {
        List<Path> changed = new ArrayList<>();
        Map<Path, FileStamp> current = new LinkedHashMap<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir, 6)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    if (isIgnored(p)) continue;
                    FileStamp st = stampOf(p);
                    current.put(p, st);
                    FileStamp prev = known.get(p);
                    if (prev == null || !prev.equals(st)) {
                        changed.add(p);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        // Detect deletions inside this root
        List<Path> deleted = new ArrayList<>();
        for (Path prevPath : known.keySet()) {
            if (prevPath.startsWith(dir) && !current.containsKey(prevPath)) {
                deleted.add(prevPath);
                changed.add(prevPath);
            }
        }
        for (Path d : deleted) known.remove(d);
        known.putAll(current);
        return changed;
    }

    private static boolean isIgnored(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.contains("/.cache/") || s.contains("/.liskov-vcs/") || s.endsWith(".tmp");
    }

    private static FileStamp stampOf(Path p) {
        try {
            return new FileStamp(Files.getLastModifiedTime(p).toMillis(), Files.size(p));
        } catch (IOException e) {
            return new FileStamp(0L, -1L);
        }
    }
}
