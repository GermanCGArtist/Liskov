# Migrating Manhattan onto Liskov

**Source repository analyzed:** `f:\workflow\Manhattan` (read-only audit against Liskov `0.1.0+mc26.3`).
**Goal:** Eliminate Manhattan's duplicated Dear ImGui / RenderPearl / native loader / font / shader stack in favor of `:liskov-ui`, align config/projects/scripts/captures with `LiskovFs` (`LiskovNamespace` `"manhattan"`) + `LiskovMigration`, and map Fleet shared-project concepts onto `:liskov-core` / `:liskov-net`.

> [!IMPORTANT]
> Check [Gaps & Follow-ups](file:///f:/workflow/Liskov/docs/GAPS_AND_FOLLOWUPS.md) before removing Manhattan's `KeyboardHandlerMixin` or `MouseHandlerMixin`! Manhattan's docked-viewport workspace (`Shell`) depends on mouse-grab blocking, docked-quad coordinate remapping, repeat-key forwarding, and a dedicated `Inter` UI font that Liskov `0.1.0+mc26.3` does not yet expose (`P0` / `P1`).

---

## 1. File-by-File Mapping Summary

| Manhattan Component | Current Location | Liskov Replacement | Action |
| --- | --- | --- | --- |
| Shaded ImGui + natives | `Manhattan/build.gradle` (`shade` `imgui-java-*`) | Bundled by `liskov` (`:liskov-ui`) | Remove `shade` configuration and duplicate ImGui natives |
| ImGui lifecycle & RenderPearl renderer | `ImGuiManager`, `ImGuiRenderPearlRenderer`, `ImGuiNatives`, `assets/baritonequeue/shaders/core/imgui_game_quad.fsh` | `LiskovUiHost`, `HostCore`, `RpRenderer`, `ImGuiNatives`, `assets/liskov/shaders/core/imgui_game_quad.fsh` | Migrate `Shell` & `Overlay` to `UiLayer` *(after `P0` docked-mouse & grab hooks land)* |
| Input mixins | `dev.manhattan.mixin.{MinecraftMixin, KeyboardHandlerMixin, MouseHandlerMixin}` | `dev.liskov.ui.mixin.{KeyboardHandlerMixin, MouseHandlerMixin, GuiRendererMixin}` | Retire `KeyboardHandlerMixin`; keep `MinecraftMixin` screenshot hook if needed, and keep `MouseHandlerMixin` docked-quad/grab hooks until `P0` lands |
| Theme & widgets | `dev.manhattan.UiTheme`, `dev.manhattan.Widgets` | `dev.liskov.ui.theme.MubTheme`, `dev.liskov.ui.theme.MubWidgets` | Map palette tokens, fonts, badges, dot-grid backdrop, pixel segments |
| Toasts & Command Palette | `Overlay` toast queue, `dev.manhattan.CommandPalette` | `MubWidgets.ToastHost`, `MubWidgets.CommandPalette` | Register Manhattan commands (`baritonequeue`, `Run`, `Pause`, `Open Project`, etc.) |
| `ItemIcons` atlas texture | `dev.manhattan.ItemIcons` | `LiskovUiHost.registerTexture` / `unregisterTexture` | Avoid `setTextureLookup` collision with sibling mods |
| Legacy `<gameDir>` storage & `Migrate` | `Config` (`config/manhattan.json`), `ProjectStore` (`config/manhattan-projects/*.json`), `scripts/*.txt`, `manhattan-context/*.txt`, `Migrate.java` | `LiskovFs.get().manhattanDir()`, `LiskovMigration`, `FileTypeRegistry.{MPROJ, MSCRIPT, MCAP}`, `ReloadWatcher`, `VcsStore` | Point `Config`, `ProjectStore`, `DockedPanels`, and `Capture` to `manhattanDir()` with `.mproj`/`.mscript`/`.mcap` support |
| Shared project folders & status | `FleetPanel`, `FleetClient` shared-project parts | `LiskovServerStore`, `FolderCatalog`, `CompletionTag`, `LockGuard`, `LiskovReloadBus` | Keep `FleetProtocol` for Midtown path intents/leases/deaths/chest reservations; offload project/file sharing to Liskov |

---

## 2. Gradle & `fabric.mod.json` Changes

### `f:\workflow\Manhattan\build.gradle`

Remove the `shade` configuration and `jar { from configurations.shade ... }` block so Manhattan does not bundle a second copy of `imgui-java-binding` or native `.dll`/`.so`/`.dylib` libraries:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"

    modImplementation "dev.liskov:liskov:${project.liskov_version}"
}
```

### `src/main/resources/fabric.mod.json`

Add `"liskov": ">=0.1.0"` under `"depends"`:

```json
  "depends": {
    "fabricloader": ">=0.20.1",
    "fabric-api": "*",
    "minecraft": "~26.3",
    "liskov": ">=0.1.0",
    "java": ">=25"
  }
```

---

## 3. ImGui Host, `Shell`, `Overlay`, Theme & Command Palette Cutover

### Registering `Shell` and `Overlay` as `UiLayer`s

In `QueueMod.onInitializeClient()`, register Manhattan's workspace (`Shell`) and closed-HUD overlay (`Overlay`) via `LiskovUiHost.register(UiLayer)`:

```java
LiskovUiHost.register(new UiLayer() {
    @Override public String id() { return "manhattan.shell"; }
    @Override public int order() { return 30; }
    @Override public boolean visible() { return Shell.isOpen(); }
    @Override public float[] dockedGameViewport() {
        return Shell.isOpen() ? Shell.computeDockedViewportRect() : null;
    }
    @Override public void draw() {
        Shell.drawInSharedHost();
    }
});

LiskovUiHost.register(new UiLayer() {
    @Override public String id() { return "manhattan.overlay"; }
    @Override public int order() { return 15; }
    @Override public boolean visible() { return !Shell.isOpen() && Overlay.visible(); }
    @Override public boolean wantsKeyboard() { return false; }
    @Override public boolean wantsMouse() { return false; }
    @Override public void draw() { Overlay.draw(); }
});
```

### Theme, Fonts & Widget Token Mapping

- `UiTheme` -> `MubTheme` (`Preset`, `BG`, `PANEL`, `PANEL2`, `SURFACE`, `LINE`, `BORDER_HI`, `FG`, `DIM`, `GUTTER`, `ACCENT`, `CTA_BG`, `CTA_FG`, `OK`, `WARN`, `ERR`, `BOT`, `PLAYER`, `CF`, `META`, `BTN`, `SEL`, `RUNLINE`).
- `Widgets` -> `MubWidgets`:
  - `paintDotGridBackdrop(...)`, `sectionHeader(...)`, `termButton(...)`, `termToggle(...)`, `termBadge(...)`, `pixelSegments(...)`.
- **`ItemIcons` texture lookup:** Replace custom RenderPearl texture hook in `ImGuiRenderPearlRenderer` with `LiskovUiHost.registerTexture(gpuView)` (keep track of returned `long` IDs and free via `LiskovUiHost.unregisterTexture(id)`).

### `MubWidgets.ToastHost` & `MubWidgets.CommandPalette`

Register Manhattan commands into Liskov's shared `Ctrl+P` palette during `QueueMod.onInitializeClient()`:

```java
MubWidgets.CommandPalette.register(new MubWidgets.CommandPalette.Entry(
        "manhattan.shell.toggle", "Toggle Manhattan Workspace (J)", "Mp",
        SemanticColor.MR, ImGuiManager::toggleShell));

MubWidgets.CommandPalette.register(new MubWidgets.CommandPalette.Entry(
        "manhattan.script.run", "Run Active Manhattan Script", "Mp",
        SemanticColor.CTA, () -> QueueMod.startActiveScript()));
```

Push status notifications through `MubWidgets.ToastHost.push("Script finished", SemanticColor.MR);`.

*(Note: Keep Manhattan's `CommandPalette` group headers/arrow-key navigation notes in mind—see `P2` in `GAPS_AND_FOLLOWUPS.md`.)*

---

## 4. Filesystem Cutover (`LiskovFs`, `LiskovMigration`, `ReloadWatcher`, `VcsStore`)

Liskov creates `<gameDir>/liskov/local/manhattan/` (`LiskovFs.get().manhattanDir()`, badge `"Mp"`, extensions `.mproj`, `.mscript`, `.mcap`).

`LiskovMigration.migrateLegacyOnce(LiskovFs)` non-destructively copies existing Manhattan data:

| Legacy Path | Liskov Destination | Extension Change |
| --- | --- | --- |
| `<configDir>/manhattan.json` (or `baritonequeue.json`) | `local/manhattan/config/manhattan.json` | Same (`.json`) |
| `<configDir>/manhattan-projects/**/*.json` | `local/manhattan/projects/**/*.mproj` | `.json` -> `.mproj` (skips `_*`) |
| `<gameDir>/scripts/**/*.txt` | `local/manhattan/scripts/**/*.mscript` | `.txt` -> `.mscript` |
| `<gameDir>/manhattan-context/**/*.txt` | `local/manhattan/captures/**/*.mcap` | `.txt` -> `.mcap` |

**Concrete code updates in Manhattan:**

1. **`Config.java`:**
   Resolve `manhattan.json` from `LiskovFs.get().manhattanDir().resolve("config/manhattan.json")` when `LiskovFs.isInitialized()`, falling back to `FabricLoader.getInstance().getConfigDir().resolve("manhattan.json")`.
   Resolve `captureDir` (when blank) to `LiskovFs.get().manhattanDir().resolve("captures")`, and death storage (`MidtownEngine.configureDeathStorage(...)`) under `LiskovFs.get().manhattanDir().resolve("deaths")`.
2. **`ProjectStore.java`:**
   Update `ProjectStore` to accept both `.mproj` (`FileTypeRegistry.MPROJ`) and `.json` when listing/loading projects from `LiskovFs.get().manhattanDir().resolve("projects")`, and write new/saved project files with `.mproj`. Keep `_active.txt` and `_queue.json` compatible (note `LiskovMigration` intentionally skips `_*` metadata files).
3. **`DockedPanels` (scripts) & `Capture` (captures):**
   - Point script directory to `LiskovFs.get().manhattanDir().resolve("scripts")` and read/write `*.mscript` (falling back to `*.txt` if reading legacy).
   - Point capture sheets to `LiskovFs.get().manhattanDir().resolve("captures")` with `*.mcap`.
4. **`VcsStore` snapshots & `ReloadWatcher`:**
   - Whenever a `.mproj` or `.mscript` is saved in `DockedPanels` / `ProjectPanel`, snapshot it via `new VcsStore(LiskovFs.get().vcsRootDir()).snapshot(...)`.
   - Subscribe to `LiskovClientMod.WATCHER` (`ReloadWatcher`, which already watches `LiskovFs.get().manhattanDir()`) or `LiskovReloadBus` to hot-reload modified scripts/projects edited externally (for example in `InspectorPane` or VS Code).

---

## 5. Fleet & Server Sync Integration

As specified in Manhattan's `docs/LISKOV_SPEC.md`, Manhattan keeps **live bot runtime coordination** (`FleetClient` / `FleetProtocol` / `FleetWire`) separate from **file & project synchronization** (Liskov):

| Concern | Owner | Notes |
| --- | --- | --- |
| Live worker leases, task claims, path intents (`PeerTraffic`), death hazards, chest reservations | `FleetClient` / `FleetProtocol` / `manhattan-fleet-server` | Keep in Manhattan—real-time ephemeral bot runtime state |
| Shared `.mproj`, `.mscript`, `.mcap`, `.litematic` distribution, folder taxonomy, ACLs, locks, version history | Liskov (`LiskovServerStore`, `FolderCatalog`, `AclConfig`, `LockGuard`, `LiskovSyncClient`) | Replace ad-hoc file pushing in `FleetClient` (`cachedProjects`) with Liskov Server Drives |
| Live project status badge in Explorer & Inspector | `CompletionTag` via `FileActionRegistry.registerStatusProvider` | Derive status with `CompletionTag.deriveProjectTag(locked, hasError, active, paused, doneChunks, totalChunks)` |

---

## 6. Registries, `FilePickerModal` & `MenuOverviewRegistry`

### `FileActionRegistry` (Explorer Actions & Live `CompletionTag` Badges)

```java
FileActionRegistry.registerStatusProvider((file, type) -> {
    if (!type.id().equals(FileTypeRegistry.MPROJ.id()) && !type.id().equals(FileTypeRegistry.MSCRIPT.id())) {
        return null;
    }
    // Derive from GameProjectRuntime / ProjectQueue state:
    return CompletionTag.deriveProjectTag(locked, hasError, active, paused, doneChunks, totalChunks);
});

FileActionRegistry.registerAction(new FileActionRegistry.Action(
        "manhattan.queue_script",
        "OPEN / RUN IN MANHATTAN",
        SemanticColor.MR,
        ft -> ft.id().equals(FileTypeRegistry.MSCRIPT.id()) || ft.id().equals(FileTypeRegistry.MPROJ.id()),
        path -> {
            // Load into DockedPanels / ProjectPanel and open Shell
        }
));
```

### `FilePickerModal` (Selecting Schematics for `schem_build()`)

```java
FilePickerModal.openPicker(
        new FilePickerModal.Request(
                "Pick Schematic for Manhattan",
                Set.of(FileTypeRegistry.LITEMATIC.id(), FileTypeRegistry.VPROJ.id()),
                LiskovFs.get().schematicsDir()
        ),
        path -> {
            // Insert #schem_build("<relPath>") or attach to Capture sheet
        }
);
```

### `MenuOverviewRegistry` (Title & Pause Screen Overview Card)

```java
MenuOverviewRegistry.get().register(new MenuOverviewRegistry.CardProvider() {
    @Override public String id() { return "manhattan.overview"; }
    @Override public String title() { return "MANHATTAN"; }
    @Override public SemanticColor accentRole() { return SemanticColor.MR; }
    @Override public int order() { return 30; }
    @Override public boolean supports(MenuOverviewRegistry.Surface s) {
        return s == MenuOverviewRegistry.Surface.TITLE_SCREEN || s == MenuOverviewRegistry.Surface.PAUSE_MENU;
    }
    @Override public void drawCardBody(MenuOverviewRegistry.Surface s) {
        MubWidgets.pixelSegments(progressFraction, 12, SemanticColor.MR, 180f, 8f);
        if (MubWidgets.termButton("OPEN SHELL (J)", SemanticColor.MR)) {
            ImGuiManager.openShell(Shell.Mode.EDIT);
        }
    }
});
```

---

## 7. Ordered Cutover Checklist & Verification

1. **Wait on / apply Liskov `v0.1.1` P0 input hooks:** Ensure `LiskovUiHost` supports `blocksMouseGrab()`, docked-viewport mouse remapping, modifier keys (`ModCtrl`/`ModShift`), and `GLFW_REPEAT` before deleting Manhattan's `MouseHandlerMixin` / `KeyboardHandlerMixin`!
2. **Gradle & `fabric.mod.json`:** Replace shaded ImGui + natives in `Manhattan/build.gradle` with `dev.liskov:liskov` and declare `"liskov": ">=0.1.0"`.
3. **Storage & Extensions Cutover:** Update `Config`, `ProjectStore`, `DockedPanels`, and `Capture` to use `LiskovFs.get().manhattanDir()` (`config/`, `projects/*.mproj`, `scripts/*.mscript`, `captures/*.mcap`) with read fallback for legacy `.json`/`.txt`.
4. **VCS & Reload Integration:** Snapshot `.mproj`/`.mscript` saves into `VcsStore`; wire `ReloadWatcher` / `LiskovReloadBus` listeners.
5. **UI Host & Theme Cutover:** Port `ImGuiManager`/`Shell`/`Overlay` onto `LiskovUiHost` (`UiLayer`), `MubTheme`, `MubWidgets`, `ToastHost`, and `CommandPalette`; migrate `ItemIcons` to `LiskovUiHost.registerTexture`.
6. **Explorer & Overview Integrations:** Register `FileActionRegistry` action + `CompletionTag` status provider, `MenuOverviewRegistry` card, and `FilePickerModal` schematic chooser.
7. **Verification:**
   - Run Manhattan offline checks (`langTest` and `parseScripts`) after updating script paths/extensions.
   - Launch MC 26.3 client with `liskov` + `manhattan`: verify `<gameDir>/liskov/.migrated` copies `scripts/*.txt` -> `local/manhattan/scripts/*.mscript`, `J` opens `Shell` with working docked viewport + keyboard editing + cursor capture, `Ctrl+P` lists Manhattan commands, and `F8` shows live `CompletionTag` badges on `.mproj`/`.mscript` files.
