# Liskov API Reference

**Baseline version:** `0.1.0+mc26.3` (`group = "dev.liskov"`, Java `25`, Minecraft `26.3`).

All method signatures, record components, constants, and constraints below match the source tree across `:liskov-core`, `:liskov-net`, and `:liskov-ui`.

---

## Table of Contents

- [Module 1: `:liskov-core` (`dev.liskov.core.*`)](#module-1-liskov-core-devliskovcore)
- [Module 2: `:liskov-net` (`dev.liskov.net`)](#module-2-liskov-net-devliskovnet)
- [Module 3: `:liskov-ui` (`dev.liskov.ui.*`)](#module-3-liskov-ui-devliskovui)

---

## Module 1: `:liskov-core` (`dev.liskov.core.*`)

Pure Java 25 + Gson library with zero Minecraft or ImGui dependencies. Safe to use in headless tests, server code, or external tools.

### `dev.liskov.core.LiskovFs`

Singleton entry point managing `<gameDir>/liskov/`, registered namespaces, `LiskovDrives`, and `FileTypeRegistry`.

- **Initialization & lookup:**
  - `public static synchronized LiskovFs init(Path gameDir, Path legacyConfigDir) throws IOException`
  - `public static LiskovFs get()` (throws `IllegalStateException` before `init`)
  - `public static boolean isInitialized()`
- **Namespaces:**
  - `public synchronized LiskovNamespace registerNamespace(LiskovNamespace ns) throws IOException`
  - `public synchronized List<LiskovNamespace> namespaces()`
  - `public synchronized LiskovNamespace namespace(String id)`
  - Default local namespaces under `<gameDir>/liskov/local/`: `"schematics"` (`Lm`), `"manhattan"` (`Mp`), `"vitruvius"` (`Vp`), `"heavymatica"` (`Hm`).
- **Directory & store accessors:**
  - `Path gameDir()`, `Path legacyConfigDir()`, `Path liskovRoot()`, `Path localRoot()`
  - `Path schematicsDir()`, `Path manhattanDir()`, `Path vitruviusDir()`, `Path heavymaticaDir()`
  - `Path trashDir()`, `Path cacheThumbsDir()`, `Path vcsRootDir()`, `Path uiConfigFile()`, `Path favoritesFile()`
  - `LiskovDrives drives()`, `FileTypeRegistry fileTypes()`
- **OS integration:**
  - `void setClipboardBridge(Consumer<String> bridge)` (`LiskovClientMod` wires this to `Minecraft.keyboardHandler.setClipboard`)
  - `boolean copyPathToClipboard(Path path)`
  - `boolean openInExplorer(Path target)`

```java
LiskovFs fs = LiskovFs.get();
Path vprojPath = fs.schematicsDir().resolve("Projects/Castle/project.vproj");
```

### `dev.liskov.core.LiskovNamespace`

```java
public record LiskovNamespace(
        String id,
        String displayName,
        Path rootDir,
        SemanticColor accentRole,
        String badge,
        List<String> extensions
) {}
```

### `dev.liskov.core.LiskovDrives` & `dev.liskov.core.DriveMount`

Manages the `LOCAL` drive (`"local"`) and persistent UUID server drives under `<liskovRoot>/servers/<serverId>/`.

- **Constants & types (`LiskovDrives`):**
  - `public static final String LOCAL_DRIVE_ID = "local";`
  - `public enum TransferKind { MOVED, COPIED }`
  - `public record TransferResult(TransferKind kind, Path source, Path destination)`
- **Methods (`LiskovDrives`):**
  - `public DriveMount local()`
  - `public synchronized DriveMount get(String driveId)`
  - `public synchronized List<DriveMount> allDrives()` (`local` first, then server drives)
  - `public synchronized List<DriveMount> serverDrives()`
  - `public synchronized DriveMount mountServerOnline(String serverId, String displayName) throws IOException` (creates `<liskovRoot>/servers/<safeId>/root/` and `drive.json`)
  - `public synchronized void markOffline(String serverId)` (`null` marks all server drives `SERVER_OFFLINE`)
  - `public synchronized TransferResult transfer(DriveMount srcDrive, String srcRelative, DriveMount dstDrive, String dstDirRelative) throws IOException`
    - *Semantics:* Same-drive transfers **MOVE** (`MOVED`); cross-drive transfers **COPY** (`COPIED`). Writing to an offline server drive throws `IllegalStateException`.
- **`DriveMount`:**
  - `public enum Kind { LOCAL, SERVER_ONLINE, SERVER_OFFLINE }`
  - `String driveId()`, `String displayName()`, `void setDisplayName(String)`, `Kind kind()`, `void setKind(Kind)`
  - `boolean isLocal()`, `boolean isOffline()`, `boolean isWritable()`, `Path rootDir()`, `long lastConnectedMs()`, `void markConnectedNow()`
  - `CacheState cacheStateOf(String relativePath)`, `void setCacheState(String relativePath, CacheState state)`
  - `boolean isPinnedOffline(String relativePath)`, `void setPinnedOffline(String relativePath, boolean pinned)`, `Set<String> pinnedOfflinePaths()`
  - `void saveMetadata() throws IOException`
  - `static String normalizeRel(String rel)`
- **`CacheState`:** `enum CacheState { LOCAL, CLOUD, CACHED, PINNED_OFFLINE }`

### `dev.liskov.core.LiskovPaths`

Static path-safety and atomic I/O utilities:

- `static void writeAtomicUtf8(Path target, String content) throws IOException`
- `static void writeAtomicBytes(Path target, byte[] bytes) throws IOException`
- `static String safeName(String raw)`
- `static String uniqueName(String desired, Predicate<String> exists)`
- `static Path resolveInside(Path base, String relative)` (throws `IllegalArgumentException` on traversal outside `base`)
- `static String sha256Hex(byte[] data)`, `static String sha256Hex(String utf8)`
- `static int copyMissingRecursive(Path srcDir, Path dstDir, Predicate<Path> filter) throws IOException`
- `static void copyDirectoryOverwrite(Path srcDir, Path dstDir) throws IOException`
- `static void deleteRecursive(Path root) throws IOException`

### `dev.liskov.core.LiskovMigration`

- `public static final class Receipt { public int version = 1; public long migratedAtMs; public List<String> copiedFiles; }`
- `public static Receipt migrateLegacyOnce(LiskovFs fs) throws IOException` — Idempotent via `<liskovRoot>/.migrated`. Non-destructively copies missing files from legacy `<gameDir>/schematics/`, `config/manhattan.json` (or `baritonequeue.json`), `config/manhattan-projects/`, `scripts/`, `manhattan-context/`, `config/vitruvius/`, and `config/heavymatica/`.

### `dev.liskov.core.LiskovFileType`, `dev.liskov.core.FileTypeRegistry`, `dev.liskov.core.SemanticColor`, `dev.liskov.core.CompletionTag`

- **`LiskovFileType`:**
  - `public enum PreviewKind { NONE, SCHEMATIC_3D, TEXT_EDITABLE, TEXT_READONLY }`
  - `public record LiskovFileType(String id, String primaryExtension, List<String> aliases, String monogram, String displayName, SemanticColor colorRole, PreviewKind previewKind)`
- **`FileTypeRegistry` built-in constants:**
  - `LITEMATIC` (`"litematic"`, `.litematic`, `"Lm"`, `SCHEMATIC_3D`)
  - `VPROJ` (`"vproj"`, `.vproj`, alias `project.json`, `"Vp"`, `SCHEMATIC_3D`)
  - `LASM` (`"lasm"`, `.lasm`, `"La"`, `SCHEMATIC_3D`)
  - `MPROJ` (`"mproj"`, `.mproj`, `"Mp"`, `TEXT_EDITABLE`)
  - `MSCRIPT` (`"mscript"`, `.mscript`, `"Ms"`, `TEXT_EDITABLE`)
  - `MCAP` (`"mcap"`, `.mcap`, `"Mc"`, `TEXT_READONLY`)
  - `HEAVYMATIC` (`"heavymatic"`, `.heavymatic`, `"Hm"`, `SCHEMATIC_3D`)
  - `CONFIG` (`"config"`, `.json`, `"Cf"`, `TEXT_EDITABLE`)
  - `LOG` (`"log"`, `.log`, alias `.txt`, `"Lg"`, `TEXT_READONLY`)
  - `GENERIC` (`"file"`, `""`, `"Fl"`, `NONE`)
  - Methods: `synchronized void register(LiskovFileType)`, `synchronized List<LiskovFileType> all()`, `synchronized LiskovFileType byId(String id)`, `synchronized LiskovFileType detect(Path file)`
- **`SemanticColor`:** `FOCUS("focus")`, `CTA("cta")`, `BOT("bot")`, `PLAYER("player")`, `MR("mr")`, `CF("cf")`, `WARN("warn")`, `META("meta")`, `DANGER("danger")`, `INK_MUTED("ink_muted")`; `String id()`, `static SemanticColor fromId(String)`.
- **`CompletionTag`:** `LOCKED`, `ERROR`, `IN_PROGRESS`, `PAUSED`, `DONE`, `NOT_STARTED` with `String label()`, `SemanticColor colorRole()`, and `static CompletionTag deriveProjectTag(boolean locked, boolean hasError, boolean active, boolean paused, int doneChunks, int totalChunks)`.

### `dev.liskov.core.FolderCatalog`, `FavoritesStore`, `TrashManager`, `ReloadWatcher`

- **`FolderCatalog`** (`_folders.json`):
  - `static FolderCatalog loadOrCreate(Path directory) throws IOException`, `void save(Path directory) throws IOException`
  - `void ensureDefault()`, `FolderEntry findFolder(String id)`, `FolderEntry createFolder(String name, SemanticColor colorRole)`, `boolean renameFolder(String id, String newName)`, `void setColorRole(String id, SemanticColor color)`, `void setArchived(String id, boolean archived)`, `void assignItem(String itemKey, String folderId)`, `String folderOfItem(String itemKey)`
- **`FavoritesStore`** (`favorites.json`, `MAX_RECENT = 20`):
  - `static FavoritesStore loadOrCreate(Path file)`, `void save(Path file) throws IOException`
  - `boolean isFavorite(String driveId, String relativePath)`
  - `boolean toggleFavorite(String driveId, String relativePath, String label, String fileTypeId, boolean folder)`
  - `void recordOpened(String driveId, String relativePath, String label, String fileTypeId)`
- **`TrashManager`** (`.trash/_manifest.json`):
  - `public TrashManager(Path trashDir) throws IOException`
  - `synchronized Manifest loadManifest()`, `synchronized TrashedItem moveToTrash(Path source, String fileTypeId) throws IOException`, `synchronized Path restore(String trashId) throws IOException`, `synchronized int emptyTrash() throws IOException`, `synchronized List<TrashedItem> list()`
- **`ReloadWatcher`** (`DEFAULT_POLL_MS = 2000L`, timestamp + size polling—no OS directory locks):
  - `public record ReloadEvent(String namespaceId, List<Path> changedFiles)`
  - `ReloadWatcher()`, `ReloadWatcher(long pollIntervalMs)`
  - `void watch(String namespaceId, Path directory)`
  - `void addListener(Consumer<ReloadEvent> listener)`, `void removeListener(Consumer<ReloadEvent> listener)`
  - `boolean isEnabled()`, `void setEnabled(boolean enabled)`
  - `void pollIfDue(long nowMs)`, `synchronized List<ReloadEvent> reloadNow()`

### `dev.liskov.core.LitematicReader`

Pure-Java GZIP + NBT `.litematic` parser producing palette counts and sampled voxel grids for `SchematicPreview3D`.

- `public static Summary read(Path file) throws IOException`
- `public static Summary read(byte[] rawBytes, String fallbackName) throws IOException`
- `public static int colorForBlock(String blockId)`
- `Summary` fields: `String name`, `String author`, `String description`, `int sizeX`, `int sizeY`, `int sizeZ`, `int regionCount`, `long totalBlocks`, `List<MaterialCount> topMaterials`, `int gridX`, `int gridY`, `int gridZ`, `int sampleStep`, `List<Voxel> voxels`.

### `dev.liskov.core.vcs.*` (`VcsStore`, `VcsCommit`, `VcsHead`, `VcsMergeResult`)

Thread-safe (`synchronized`) content-addressed snapshot & branching store under `.liskov-vcs/`.

- **`VcsStore(Path vcsRoot)`:**
  - `static String repoIdFor(String relativePath)` (SHA-256 of normalized relative path)
  - `boolean hasBlob(String sha256)`, `String putBlob(byte[] bytes) throws IOException`, `byte[] getBlob(String sha256) throws IOException`, `List<String> missingBlobs(Iterable<String> hashes)`
  - `synchronized VcsHead loadHead(String driveId, String repoId)`, `synchronized List<VcsCommit> loadCommits(String driveId, String repoId)`
  - `synchronized VcsCommit snapshot(String driveId, String repoId, String authorUuid, String authorName, String message, boolean milestone, boolean autoSave, Map<String, byte[]> files) throws IOException` *(auto-deduplicates identical `autoSave` snapshots and auto-prunes old autosaves + orphaned blobs)*
  - `synchronized VcsCommit snapshotDirectory(String driveId, String repoId, Path dir, String authorUuid, String authorName, String message, boolean milestone, boolean autoSave) throws IOException`
  - `synchronized void setAutoRetention(int maxAutosaves, int maxAgeDays, int keepRecent)` *(defaults: `25`, `30`, `15`)*
  - `synchronized void acceptRemoteCommit(String driveId, String repoId, VcsCommit commit, boolean makeActiveHead) throws IOException`
  - `synchronized Map<String, byte[]> readCommitFiles(String driveId, String repoId, String commitId) throws IOException`
  - `synchronized RestoreOutput restore(String driveId, String repoId, String targetCommitId, String actorUuid, String actorName) throws IOException` (`RestoreOutput(VcsCommit commit, Map<String, byte[]> files)`)
  - `synchronized boolean setMilestone(String driveId, String repoId, String commitId, boolean milestone) throws IOException`
  - `synchronized void createBranch(String driveId, String repoId, String newBranch) throws IOException`
  - `synchronized Map<String, byte[]> switchBranch(String driveId, String repoId, String branch) throws IOException`
  - `synchronized VcsMergeResult mergeBranch(String driveId, String repoId, String sourceBranch, String actorUuid, String actorName) throws IOException`
  - `synchronized int prune(String driveId, String repoId, int maxAgeDays, int keepRecentCount) throws IOException`
  - `synchronized int gcUnreferencedObjects() throws IOException`
- **`VcsHead`:** `activeBranch` (default `"main"`), `headCommitId`, `latestVersion`, `Map<String, String> branchTips`.
- **`VcsMergeResult`:** `enum Status { UP_TO_DATE, FAST_FORWARD, MERGED_CLEANLY, MERGED_WITH_CONFLICTS }`, `VcsCommit mergedCommit`, `List<String> conflictedPaths`.

```java
VcsStore vcs = new VcsStore(LiskovFs.get().vcsRootDir());
String rel = "Projects/Tower/project.vproj";
VcsCommit commit = vcs.snapshot(
        LiskovDrives.LOCAL_DRIVE_ID,
        VcsStore.repoIdFor(rel),
        playerUuid,
        playerName,
        "Update floor 3",
        false,
        true,
        Map.of("project.vproj", vprojBytes, "files/roof.litematic", roofBytes)
);
```

### `dev.liskov.core.acl.*` & `dev.liskov.core.sync.*`

- **`AclRole`:** `NONE(0)`, `VIEW(1)`, `EDIT(2)`, `ADMIN(3)`; `boolean allows(AclRole required)`.
- **`AclConfig`** (`acl.json`): `loadOrCreate(Path)`, `save(Path)`, `ensureDefaultPolicy()`, `policyFor(String targetId)`, `getOrCreatePolicy(String targetId, String ownerUuid, String ownerName)`, `addTeamMember(String teamName, String playerUuid)`, `removeTeamMember(String teamName, String playerUuid)`.
- **`AclGroup`:** `id`, `ownerUuid`, `ownerName`, `AclRole defaultRole`, `Map<String, AclRole> memberRoles`, `Map<String, AclRole> groupRoles`, `Map<String, String> playerNames`.
- **`AccessEvaluator`:** `effectiveRole(AclConfig, targetId, playerUuid, isOp)`, `canView(...)`, `canEdit(...)`, `canAdmin(...)`.
- **`LockGuard`:**
  - `public static final String OP_CONFIRM_TOKEN = "UNLOCK";`
  - `public record Decision(boolean allowed, String reason)`
  - `static Decision canUnlock(LockState lock, String actorUuid, boolean isOp, String confirmText)`
  - `static boolean canModify(LockState lock, String actorUuid)` (author or locker only when locked)
- **`ServerIdentity`:** Persistent `serverId` (UUID) + `displayName` stored in `server.json`.
- **`SyncWire`:** Channel `"liskov:sync"`, `PROTOCOL_VERSION = 1`, `CHUNK_SIZE = 64 * 1024`, `MAX_JSON_BYTES = 512 * 1024`, `MAX_CHUNK_BYTES = 128 * 1024`, packet type constants (`HELLO`, `TREE_SNAPSHOT`, `NEED_BLOBS`, `BLOB_CHUNK`, `PUSH_ACK`, `REPO_HEAD_UPDATED`, `REPO_DELETED`, `HISTORY_RESPONSE`, `ERROR`, `PUSH_CHECK`, `PUSH_COMMIT`, `PULL_REQUEST`, `HISTORY_REQUEST`, `LOCK_MUTATE`, `ACL_MUTATE`, `DELETE_REPO`, `RENAME_REPO`, `FOLDER_MUTATE`) and nested DTOs (`FolderSummary`, `RepoEntry`, `HelloBody`, `PushCheckBody`, `NeedBlobsBody`, `BlobChunkMeta`, `RepoUpdatedBody`, `PullRequestBody`, `LockMutateBody`, `AclMutateBody`, `RepoDeleteBody`, `RepoRenameBody`, `RepoDeletedBody`, `FolderMutateBody`, `HistoryResponseBody`).
- **`LiskovServerStore`:**
  - `public static final String DEFAULT_FOLDER_ID = "shared";`
  - `LiskovServerStore(Path serverRoot, String defaultDisplayName)` managing `server.json`, `acl.json`, `shared-folders/`, and `.liskov-vcs/`.
  - `listVisibleFolders(...)`, `buildHello(...)`, `missingBlobs(...)`, `storeBlob(...)`, `readBlob(...)`, `acceptCommit(...)`, `mutateLock(...)`, `setAclRole(...)`, `historyOf(repoId)`, `findFolderIdOfRepo(repoId)`, `deleteRepo(...)`, `renameRepo(...)`, `mutateFolder(...)`, `revertRepo(...)`.

---

## Module 2: `:liskov-net` (`dev.liskov.net`)

Common Fabric `ModInitializer` (`LiskovNetMod`) registering `liskov:sync` and driving server/client sync.

### `dev.liskov.net.LiskovPayload`

- Implements `CustomPacketPayload` for ID `liskov:sync` (`ResourceLocation.fromNamespaceAndPath("liskov", "sync")`).
- Record: `(String json, byte[] data)` with bounds enforcement (`SyncWire.MAX_JSON_BYTES`, `SyncWire.MAX_CHUNK_BYTES`).

### `dev.liskov.net.LiskovReloadBus`

In-process event bus (`CopyOnWriteArrayList<Listener>`) called on the Minecraft client thread when sync updates arrive or conflicts occur.

- `public record ReloadNotice(String driveId, String relativePath, int versionNum, String authorName)`
- `public record ConflictNotice(String serverId, String repoId, String relativePath, int serverVersion, String authorName, String commitId)`
- `public interface Listener { void onReload(ReloadNotice notice); default void onConflict(ConflictNotice conflict) {} }`
- Static methods: `subscribe(Listener)`, `unsubscribe(Listener)`, `fireReload(ReloadNotice)`, `fireConflict(ConflictNotice)`

### `dev.liskov.net.LiskovSyncClient`

Client-side `liskov:sync` singleton (`LiskovSyncClient.get()`). All packet handlers marshal onto `context.client().execute(...)`.

- `public synchronized void initClientNetworking()` (called automatically by `LiskovClientMod`)
- `public String activeServerId()`, `public String activeServerName()`
- `public List<SyncWire.FolderSummary> foldersOf(String serverId)`
- `public Map<String, LiskovReloadBus.ConflictNotice> activeConflicts()`
- `public void clearConflict(String repoId)`
- `public void setDirty(String repoId, boolean dirty)` — prevents auto-overwriting unsaved local edits; instead emits `LiskovReloadBus.ConflictNotice`.
- `public void setOpen(String repoId, boolean open)` — marks currently open files for automatic `PULL_REQUEST` + `ReloadNotice` on remote head updates.
- `public String findFolderIdForRepo(String serverId, String repoId)`
- `public List<VcsCommit> cachedHistory(String repoId)`
- `public void requestDownload(SyncWire.RepoEntry entry)` — fetches missing blobs into local `.liskov-vcs/`, materializes under `servers/<serverId>/root/`, sets `CacheState.CACHED`.
- `public void pushCommit(String folderId, String relativePath, String fileTypeId, VcsCommit commit, Map<String, byte[]> files)` — caches any provided `files` blobs and initiates `PUSH_CHECK -> NEED_BLOBS -> BLOB_CHUNK -> PUSH_COMMIT`.
- `public VcsCommit publishPath(String folderId, Path localFileOrDir, String serverRelativePath, String fileTypeId, String authorUuid, String authorName, String message, boolean autoSave) throws IOException` — mirrors a local file/folder onto the active server drive, snapshots in `VcsStore`, and pushes over `liskov:sync`.
- `public void requestHistory(String repoId)` / `requestHistory(String repoId, Consumer<List<VcsCommit>> onResult)`
- `public void requestLockToggle(String folderId, String repoId, boolean lock, String reason, String confirmUnlockText)`
- `public void requestAclMutate(String targetId, String subjectUuid, String subjectName, boolean isTeam, AclRole role)`
- `public void requestDelete(String folderId, String repoId)` / `requestRename(String folderId, String repoId, String newRelativePath)` / `requestFolderMutate(SyncWire.FolderMutateBody body)`
- `public void setErrorListener(Consumer<String> listener)`

---

## Module 3: `:liskov-ui` (`dev.liskov.ui.*`)

Client-only Fabric layer (`LiskovClientMod`) hosting Dear ImGui `1.90.0` over RenderPearl.

### `dev.liskov.ui.host.LiskovUiHost` & `dev.liskov.ui.host.UiLayer`

- **`LiskovUiHost` static facade:**
  - `enum Cursor { AUTO, ARROW, TEXT, CROSSHAIR, HAND, RESIZE_EW, RESIZE_NS, RESIZE_ALL, NOT_ALLOWED }`
  - `static void register(UiLayer layer)`
  - `static MubTheme.Preset themePreset()`, `static void setThemePreset(MubTheme.Preset preset)`
  - `static float scale()`, `static void setScale(float scale)`
  - `static boolean isInitialized()`
  - `static boolean imguiWantsKeyboard()`, `static boolean imguiWantsMouse()`, `static boolean imguiWantsTextInput()`
  - `static void setCursor(Cursor cursor)`, `static void captureCursor(boolean capture)`, `static boolean isCursorCaptured()`
  - `static boolean isKeyDown(int glfwKey)`, `static boolean isMouseDown(int glfwButton)`, `static double cursorX()`, `static double cursorY()`
  - `static boolean blocksMouseGrab()`, `static boolean isDockedViewportActive()`, `static double remapMouseX(double x)`, `static double remapMouseY(double y)`
  - `static boolean isBackendSupported()`, `static void notifyResourcesReload()`
  - `static long registerTexture(GpuTextureView view)`, `static void unregisterTexture(long texId)`
  - `static void setTextureLookup(LongFunction<GpuTextureView> lookup)` *(Single global slot—prefer `registerTexture`/`unregisterTexture` across multiple mods.)*
- **`UiLayer` interface:**
  - `String id()`, `int order()`, `boolean visible()`, `void draw()`
  - Defaults: `wantsKeyboard()`, `wantsMouse()`, `blocksMouseGrab()`, `onResourcesReload()`, `wantsKey(int key, int scancode, int action, int mods)`, `onKey(...)`, `onMouseButton(...)`, `onMouseMove(...)`, `onScroll(...)`, `float[] dockedGameViewport()` (`[x, y, w, h]` in display pixels; first non-null visible layer activates docked game rendering).

```java
LiskovUiHost.register(new UiLayer() {
    @Override public String id() { return "mymod.panel"; }
    @Override public int order() { return 20; }
    @Override public boolean visible() { return panelOpen; }
    @Override public void draw() {
        if (ImGui.begin("My Mod")) {
            MubWidgets.sectionHeader("STATUS");
            if (MubWidgets.termButton("RUN", SemanticColor.CTA)) { /* ... */ }
        }
        ImGui.end();
    }
});
```

### `dev.liskov.ui.host.InputMap`

Translates Minecraft 26.3 SDL scancodes/modifiers (`InputConstants`) to GLFW constants (`GLFW_PRESS`, `GLFW_RELEASE`, `GLFW_REPEAT`, `GLFW_KEY_*`, `GLFW_MOD_*`) and `ImGuiKey`.

### `dev.liskov.ui.theme.MubTheme` & `dev.liskov.ui.theme.MubWidgets`

- **`MubTheme`:**
  - `enum Preset { SYNTH_TERMINAL, GRUVBOX_PIXEL, TOKYO_NIGHT, DRAFTING_PAPER }`
  - `UiConfig`: `themePreset`, `uiScale`, `titleOverviewOpen`, `pauseOverviewOpen`, `defaultViewMode` (persisted in `<gameDir>/liskov/liskov.json`).
  - Public static color tokens (`0xRRGGBB`): `BG`, `PANEL`, `PANEL2`, `SURFACE`, `LINE`, `LINE2`, `BORDER_HI`, `DOT`, `FG`, `DIM`, `GUTTER`, `ACCENT`, `ON_ACCENT`, `CTA_BG`, `CTA_FG`, `OK`, `WARN`, `ERR`, `BOT`, `PLAYER`, `CF`, `META`, `BTN`, `CHIP`, `HOVER`, `TRACK`, `SLOT`, `SEL`, `RUNLINE`.
  - Fonts: `ui()`, `small()`, `mono()`, `monoS()`, `uiBold()`, `numM()`, `numL()`, plus `push(ImFont)`.
  - Helpers: `loadFromDisk()`, `saveToDisk()`, `setPreset(Preset)`, `cyclePreset()`, `colorFor(SemanticColor)`, `onSolid()`, `u32(int rgb)`, `u32(int rgb, float a)`, `r/g/b(int rgb)`, `textColored(int rgb, String text)`.
- **`MubWidgets`:**
  - `paintDotGridBackdrop(ImDrawList dl, float x0, float y0, float w, float h, float scale)`
  - `sectionHeader(String title)`
  - `boolean termButton(String label, SemanticColor tone)` / `(String label, SemanticColor tone, float width, float height)`
  - `boolean termToggle(String id, String label, boolean current, SemanticColor tone)`
  - `void termBadge(String text, SemanticColor tone)`
  - `void fileTypeBadge(LiskovFileType ft, boolean large)`
  - `void pixelSegments(float fraction, int segments, SemanticColor fillRole, float width, float height)`
  - **`MubWidgets.ToastHost`:** `push(String message, SemanticColor role)`, `drawOverlay()`
  - **`MubWidgets.CommandPalette`:** `record Entry(String id, String label, String badge, SemanticColor role, Runnable action)`, `register(Entry)`, `isOpen()`, `toggle()`, `close()`, `draw()`

### `dev.liskov.ui.menu.MenuOverviewRegistry`

Renders collapsible ModsUndBots summary cards on `TitleScreen` and `PauseScreen` (`order() = 50`).

- `public enum Surface { TITLE_SCREEN, PAUSE_MENU }`
- `public interface CardProvider { String id(); String title(); SemanticColor accentRole(); int order(); boolean supports(Surface surface); void drawCardBody(Surface surface); }`
- `MenuOverviewRegistry.get().register(CardProvider)`

### `dev.liskov.ui.preview.SchematicPreview3D`

RenderPearl `GpuTexture`-backed 3D voxel preview (`320x220` software depth-buffered rasterizer, works on Vulkan, OpenGL, and TitleScreen).

- `void setSummary(String key, LitematicReader.Summary summary)`
- `LitematicReader.Summary summary()`
- `void resetCamera()`
- `void drawInspectorPreview(float width)`
- `void dispose()`

### `dev.liskov.ui.explorer.*` (`LiskovExplorer`, `FileActionRegistry`, `FilePickerModal`)

- **`LiskovExplorer`:** Singleton `UiLayer` (`order() = 100`, toggle with `F8` or `LiskovExplorer.get().toggle()`). Renders Miller-column browser across Local + Server drives, drag-and-drop transfers, Favorites, `.trash/`, and `InspectorPane` (3D preview, VCS commit/restore/branch/merge, Lock/Unlock).
- **`FileActionRegistry`:**
  - `record Action(String id, String label, SemanticColor tone, Predicate<LiskovFileType> supports, Consumer<Path> onClick)`
  - `interface StatusProvider { CompletionTag statusOf(Path file, LiskovFileType type); }`
  - `registerAction(Action)`, `registerStatusProvider(StatusProvider)`, `actionsFor(LiskovFileType)`, `statusOf(Path, LiskovFileType)`
- **`FilePickerModal`:**
  - `record Request(String title, Set<String> allowedTypeIds, Path initialDir)`
  - `openPicker(Request request, Consumer<Path> onPick)`, `isOpen()`, `close()`, `draw()`
