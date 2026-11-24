# Liskov Gaps & Status Tracker (`0.2.0+mc26.3`)

Technical audit of missing hooks, input/keycode bugs, incomplete sync flows, and cross-mod rough edges discovered while mapping **Vitruvius** (`f:\workflow\Vitruvius`) and **Manhattan** (`f:\workflow\Manhattan`) onto Liskov.

## Resolution Status (`0.2.0+mc26.3`)

| # | Item | Status | Resolved In |
|---|---|---|---|
| 1 | `KeyMapping` SDL scancode (`InputConstants.KEY_F8`) & `en_us.json` | **RESOLVED** | `0.1.1+mc26.3` |
| 2 | Global `Ctrl+P`, `HostCore.onKey` layer short-circuit, modifiers & repeat | **RESOLVED** | `0.1.1+mc26.3` / `0.2.0+mc26.3` |
| 3 | Mouse-grab blocking (`UiLayer.blocksMouseGrab()`) & docked viewport remapping | **RESOLVED** | `0.2.0+mc26.3` |
| 4 | End-to-end `LiskovSyncClient` (`publishPath`, history, ACL, delete/rename/folder CRUD) & CLI | **RESOLVED** | `0.2.0+mc26.3` |
| 4b | Automatic `VcsStore` autosave dedup + retention pruning + blob GC | **RESOLVED** | `0.2.0+mc26.3` |
| 5 | Extension renames vs unmodified mod readers | **COORDINATED** | Vitruvius/Manhattan updates |
| 6 | `/liskov` CLI parity (`acl set`, `lock`, `unlock`, `history`, `revert`) | **RESOLVED** | `0.2.0+mc26.3` |

---

## P0 — Blockers (Must Fix Before Full Vitruvius / Manhattan Cutover)

### 1. `LiskovClientMod` KeyMapping Uses GLFW Code `297` Instead of MC 26.3 SDL Scancode (`InputConstants.KEY_F8`)
- **Where:** `liskov-ui/src/main/java/dev/liskov/ui/LiskovClientMod.java` (`new KeyMapping("key.liskov.explorer", 297, cat)`).
- **Problem:** Minecraft `26.3` (`InputConstants`) switched `KeyMapping` keycodes to SDL scancodes (`InputConstants.KEY_F8`, just as Vitruvius `EditorMode` uses `InputConstants.KEY_F6`). Passing raw GLFW key `297` breaks vanilla keybind display/matching, while `LiskovExplorer.onKey` separately listens for GLFW `297`.
- **Fix for `v0.1.1`:** Use `InputConstants.KEY_F8` in `KeyMapping`, and guard `ClientTickEvents` vs `LiskovExplorer.onKey` so pressing `F8` while `LiskovExplorer` is open never double-toggles within one tick.

### 2. Global `Ctrl+P` (`CommandPalette`) & `HostCore.onKey` Layer-Isolation / Modifier / Repeat Bugs
- **Where:** `liskov-ui/src/main/java/dev/liskov/ui/host/HostCore.java`, `liskov-ui/src/main/java/dev/liskov/ui/explorer/LiskovExplorer.java`, `liskov-ui/src/main/java/dev/liskov/ui/LiskovClientMod.java`.
- **Problems observed:**
  1. **`Ctrl+P` is unreachable when `LiskovExplorer` is closed:** `HostCore.routing()` returns `false` when `visibleLayers().isEmpty()`, and `Ctrl+P` is only checked inside `LiskovExplorer.onKey(...)`. When `LiskovExplorer` is closed (including on `TitleScreen`/`PauseScreen` where only `MenuOverviewRegistry` is visible), `Ctrl+P` does nothing.
  2. **No per-layer key consumption short-circuit:** `HostCore.onKey(...)` iterates all visible layers and calls `l.onKey(...)` on every one of them whenever *any* layer returns `wantsKey() == true`. Pressing `Esc` in `CommandPalette` or `FilePickerModal` leaks to Vitruvius's `EditorInputLayer` or Manhattan's `Shell`.
  3. **Missing `ImGuiKey.ModCtrl` / `ModShift` / `ModAlt` / `ModSuper` and `GLFW_REPEAT`:** `HostCore.onKey` passes `action == InputMap.GLFW_PRESS` into `io.addKeyEvent(ik, ...)` (ignoring `GLFW_REPEAT`, breaking held Backspace/Arrow keys in text editors like Manhattan's script editor) and never injects ImGui modifier key events (`Ctrl+A`/`C`/`V`/`Z` in `InputText`).
  4. **`onChar` checks `anyWantsKeyboard()` instead of text-input focus:** Should gate character injection on `imguiWantsTextInput()` or layer text-input need.
- **Fix for `v0.1.1`:** Intercept `Ctrl+P` globally in `HostCore` (outside `LiskovExplorer`), dispatch `onKey` top-to-bottom (highest `order()` first) with consumption short-circuit, forward `GLFW_REPEAT` and modifier keys to `ImGuiIO`, and wrap `CommandPalette` + `FilePickerModal` as dedicated top-order `UiLayer`s (e.g., `order = 200` / `210`).

### 3. Missing Mouse-Grab Blocking & Docked-Viewport Mouse Remapping (`MouseHandlerMixin` / `UiLayer`)
- **Where:** `liskov-ui/src/main/java/dev/liskov/ui/mixin/MouseHandlerMixin.java`, `liskov-ui/src/main/java/dev/liskov/ui/host/HostCore.java`, `liskov-ui/src/main/java/dev/liskov/ui/host/UiLayer.java`.
- **Problems observed:**
  1. Vitruvius (`MouseHandlerGrabMixin`) and Manhattan (`MouseHandlerMixin`) both hook `MouseHandler.grabMouse()` / `turnPlayer()` so gameplay never steals the cursor while an editor/workspace is active. Liskov's `MouseHandlerMixin` only hooks `onMove`, `onButton`, `onScroll`.
  2. `HostCore.onMouseMove` returns `false` whenever `mc.mouseHandler.isMouseGrabbed()` is true, and has no mechanism for Manhattan's `Shell` docked-viewport mode (`dockedGameViewport()`) to remap cursor coordinates inside the shrunk game quad into full-framebuffer coordinates (`remapMouseX` / `remapMouseY`), nor to let clicks inside the docked game quad focus gameplay without ImGui swallowing them.
- **Fix for `v0.1.1`:** Add `default boolean blocksMouseGrab()` and optional docked-quad pass-through hooks on `UiLayer`, mixin `MouseHandler.grabMouse()` / `turnPlayer()` (plus mouse coordinate remapping when `RpRenderer` renders a docked viewport), and expose `LiskovUiHost.isDockedViewportActive()`.

### 4. `LiskovSyncClient` & `LiskovNetMod` Missing End-to-End Sync Paths
- **Where:** `liskov-net/src/main/java/dev/liskov/net/LiskovSyncClient.java`, `liskov-net/src/main/java/dev/liskov/net/LiskovNetMod.java`, `liskov-ui/src/main/java/dev/liskov/ui/explorer/InspectorPane.java`, `liskov-core/src/main/java/dev/liskov/core/LiskovDrives.java`.
- **Problems observed:**
  1. `LiskovSyncClient.handleS2C` does not handle `SyncWire.HISTORY_RESPONSE` or `SyncWire.TREE_SNAPSHOT`, and `LiskovSyncClient` exposes no client API to send `HISTORY_REQUEST` or `ACL_MUTATE` (even though `LiskovNetMod` already implements both server-side!).
  2. `LiskovSyncClient.pushCommit(folderId, relPath, fileTypeId, commit, files)` ignores its `files` map parameter and looks up blobs exclusively in `LiskovFs.get().vcsRootDir()` on `NEED_BLOBS`; `InspectorPane` also hardcodes `"shared"` as `folderId`, and `InspectorPane.doRestore` never pushes restored commits to online server drives.
  3. `LiskovDrives.transfer(...)` only copies files on disk when transferring `LOCAL -> SERVER_ONLINE`—it does not snapshot into `VcsStore` or trigger `LiskovSyncClient.pushCommit(...)`, so transferred items are never synced to the server.
  4. `LiskovServerStore` has no delete/rename/folder-create/folder-rename/folder-archive RPCs in `SyncWire`.
- **Fix for `v0.1.1`:** Wire `HISTORY_REQUEST`/`HISTORY_RESPONSE`, `ACL_MUTATE`, and `TREE_SNAPSHOT` in `LiskovSyncClient`; buffer or ensure-snapshot `files` in `pushCommit`; trigger `VcsStore.snapshot` + `pushCommit` when dropping onto an online server drive; add folder management + delete/rename `SyncWire` packets.

### 5. `LiskovMigration` Extension Renames Break Legacy Mod Readers Until Mods Update
- **Where:** `liskov-core/src/main/java/dev/liskov/core/LiskovMigration.java` vs Vitruvius `ProjectLayout` / Manhattan `ProjectStore`, `DockedPanels`, `Capture`.
- **Problem:** `LiskovMigration.migrateLegacyOnce` renames `project.json -> project.vproj`, `manhattan-projects/*.json -> *.mproj`, `scripts/*.txt -> *.mscript`, and `manhattan-context/*.txt -> *.mcap` on copy into `<gameDir>/liskov/local/`. Meanwhile `LitematicaRedirectMixin` redirects Litematica's schematics folder to `<gameDir>/liskov/local/schematics/`. If a user installs Liskov `0.1.0` alongside an **unmodified** Vitruvius/Manhattan build, Vitruvius's `ProjectManager` looking for `project.json` in `schematics/Projects/` will not see migrated `.vproj` projects.
- **Fix / Coordination:** Coordinate Vitruvius (`ProjectLayout`) and Manhattan (`ProjectStore`, `DockedPanels`, `Capture`) updates to support `.vproj` / `.mproj` / `.mscript` / `.mcap` before or alongside shipping Liskov `0.1.x`.

---

## P1 — Important (Needed for Feature Parity & Smooth UX)

### 6. CLI Command Parity vs `README.md` (`LiskovNetMod`)
- **Where:** `liskov-net/src/main/java/dev/liskov/net/LiskovNetMod.java` vs [README.md](file:///f:/workflow/Liskov/README.md).
- **Problem:** `README.md` documents `/liskov` subcommands `status`, `share`, `acl`, `lock`, `unlock`, `history`, `revert`, whereas `LiskovNetMod` only registers `/liskov status`, `/liskov server name <displayName>`, and `/liskov acl team add <team> <playerUuid>`.
- **Fix for `v0.1.1`:** Implement the remaining `/liskov` server/OP subcommands (`acl set`, `lock`, `unlock`, `history`, `revert`, `share`) or align the docs.

### 7. `LiskovUiHost.setTextureLookup` Is a Single Global Slot
- **Where:** `liskov-ui/src/main/java/dev/liskov/ui/host/LiskovUiHost.java`, `RpRenderer.java`.
- **Problem:** `setTextureLookup(LongFunction<GpuTextureView>)` overwrites a single static `RpRenderer.extraTextureLookup`. If both Vitruvius (`BlockIcons`) and Manhattan (`ItemIcons`) call it, one clobbers the other.
- **Fix for `v0.1.1`:** Deprecate `setTextureLookup` (or replace with keyed/registered ranges) and guide all mods to `LiskovUiHost.registerTexture(GpuTextureView)` / `unregisterTexture(long)`. Also add a `UiLayer.onResourcesReload()` (or asset-reload event) so mods can invalidate cached texture views cleanly.

### 8. Missing Separate UI Sans Font (`Inter.ttf`) & `ToastHost` Action Buttons
- **Where:** `liskov-ui/src/main/java/dev/liskov/ui/theme/MubTheme.java`, `MubWidgets.java`, `resources/assets/liskov/fonts/`.
- **Problems:**
  - `assets/liskov/fonts/Inter.ttf` is bundled in resources, yet `MubTheme.loadFonts` loads `JetBrainsMono.ttf` for `ui`, `small`, `uiBold`, `mono`, and `monoS` (ignoring `Inter.ttf`). Manhattan's `UiTheme` uses `Inter` for readable body text and `JetBrainsMono` for code/numbers.
  - `MubWidgets.ToastHost` lacks Vitruvius's `Toasts` action buttons (e.g. `"Undo"`), line wrapping, and hover-to-pause timer.
- **Fix for `v0.1.1`:** Load `Inter.ttf` for `ui()` / `small()` / `uiBold()` (keeping `mono()` / `monoS()` on `JetBrainsMono.ttf`) and add optional `ToastHost.push(msg, role, actionLabel, onAction)` + hover pause.

### 9. `LiskovExplorer` / `InspectorPane` / `FilePickerModal` Gaps
- **3D preview hardcodes `.litematic`:** `InspectorPane` and `FilePickerModal` check `endsWith(".litematic")` explicitly instead of supporting `.vproj`, `.lasm`, and `.heavymatic` (`PreviewKind.SCHEMATIC_3D`).
- **`FilePickerModal` lacks directory-selection mode & multi-select:** Needed to replace Vitruvius's `FolderPicker`.
- **`InspectorPane` text editor buffer cap:** Fixed `32 * 1024` bytes (`30_000` char warning), whereas Manhattan scripts support up to `65_536` chars (`DockedPanels`).
- **Explorer window state not persisted:** `ExplorerWindowState` resets on restart instead of saving tabs/drives/columns to `liskov.json` (and `LiskovExplorer` cannot yet be embedded as a child panel inside Manhattan's `Shell` without top-level `ImGui.begin`).

---

## P2 — Nice-to-Have & Polish

- **`HostCore.disabled` latches permanently:** Any transient exception during ImGui frame draw permanently disables `HostCore` for the entire game session; add bounded retry / error toast + `LiskovUiHost.isBackendSupported()` (present in `cg-uihost`).
- **`CommandPalette` ergonomics:** Add Up/Down arrow + Enter keyboard selection, category grouping, and right-aligned shortcut hints (matching Manhattan's `CommandPalette`).
- **`MenuOverviewRegistry` multi-card toggle:** Currently only card `i == 0` renders the expand/collapse header button; give the overview panel a unified header bar so card `0` is not visually coupled to the global collapse toggle.
- **`VcsStore` automatic retention & `SyncWire` pagination:** Add configurable auto-prune defaults for `autoSave` commits so `.liskov-vcs/` does not grow unbounded, and split `HELLO` / `TREE_SNAPSHOT` if a large server's folder JSON approaches `SyncWire.MAX_JSON_BYTES` (`512 KiB`).
- **Optional `logs/` & `deaths/` subfolders:** Document or expose helper accessors on `LiskovFs` / `LiskovNamespace` for standardized per-mod subfolders (`config/`, `projects/`, `scripts/`, `captures/`, `deaths/`, `logs/`).
