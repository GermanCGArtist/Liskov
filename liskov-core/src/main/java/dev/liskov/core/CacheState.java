package dev.liskov.core;

/**
 * Local availability state for an item inside a Liskov drive.
 */
public enum CacheState {
    /** Stored directly on the user's private LOCAL drive. */
    LOCAL,
    /** Server drive item whose metadata is known, but payload has not been downloaded yet. */
    CLOUD,
    /** Server drive item downloaded on-demand and cached locally in .liskov-vcs/objects/. */
    CACHED,
    /** Server drive item pinned 'Keep Offline': auto-synced and live-reloaded on server updates. */
    PINNED_OFFLINE
}
