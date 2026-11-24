# Migrating Vitruvius onto Liskov

**Source repository analyzed:** `f:\workflow\Vitruvius` (read-only audit against Liskov `0.1.0+mc26.3`).
**Goal:** Retire Vitruvius's embedded `:ui-host` (`cg-uihost`), replace bespoke project/history/Syncmatica file handling with `:liskov-core` + `:liskov-net`, and integrate Vitruvius into `LiskovUiHost`, `FileActionRegistry`, `FilePickerModal`, and `MenuOverviewRegistry`.

> [!IMPORTANT]
> Check [Gaps & Follow-ups](file:///f:/workflow/Liskov/docs/GAPS_AND_FOLLOWUPS.md) before cutting over `EditorInputLayer`, `Toasts`, `BlockIcons`, or `.vproj` loading—several `v0.1.1` Liskov additions (`mouse-grab` hook, single-slot `setTextureLookup`, `.vproj` preview wiring, and action buttons on `ToastHost`) directly affect Vitruvius.

---

## 1. File-by-File Mapping Summary

| Vitruvius Component | Current Location | Liskov Replacement | Action |
| --- | --- | --- | --- |
| Embedded ImGui host | `ui-host/` (`dev.cg.uihost.*`, `cg-uihost.mixins.json`) | `:liskov-ui` (`dev.liskov.ui.host.*`, `liskov.mixins.json`) | Remove `:ui-host` subproject & `cg-uihost` dependency |
| `UiHost`, `UiLayer`, `Theme` | `dev.cg.uihost.{UiHost, UiLayer, Theme}` | `LiskovUiHost`, `UiLayer`, `MubTheme`, `MubWidgets` | Update imports & palette/font calls |
| `ImGui` compile dependency | `compileOnly "io.github.spair:imgui-java-binding:1.90.0"` | Provided transitively by `modImplementation "dev.liskov:liskov:..."` (or `:liskov-ui`) | Keep `compileOnly` or inherit from `:liskov-ui` |
| Editor toasts (`Toasts` / `ToastQueue`) | `dev.vitruvius.editor.view.Toasts` | `MubWidgets.ToastHost` *(after P1 Undo-action support lands)* | Keep `Toasts` short-term for Undo actions, or delegate non-action toasts |
| `FolderPicker` modal | `dev.vitruvius.editor.view.FolderPicker` | `FilePickerModal` *(after P1 directory-picker mode)* | Keep for directory picking until P1 lands; use `FilePickerModal` for schematic files |
| `ShapeWindow` schematic picker | `dev.vitruvius.editor.view.ShapeWindow` / `ShapeSaver` | `LiskovFs.get().schematicsDir()` + `FilePickerModal` | Point saves/loads to `LiskovFs` |
| `Projects/` layout & `project.json` | `dev.vitruvius.editor.io.ProjectLayout` (`project.json`) | `LiskovFs.get().schematicsDir()` + `FileTypeRegistry.VPROJ` (`.vproj`) | Read `.vproj` + fallback `project.json`; write `.vproj` |
| `config/vitruvius/` (`folders.json`, `editor-session.json`, `editor-loose/`) | `dev.vitruvius.Folders`, `ProjectManager` | `LiskovFs.get().vitruviusDir().resolve("config")` + `LiskovMigration` | Resolve via `LiskovFs` |
| Litematica base schematics dir | Direct `DataManager.getSchematicsBaseDirectory()` assumptions | Automatically redirected by `Liskov` `LitematicaRedirectMixin` to `<gameDir>/liskov/local/schematics/` | Remove hard-coded `<gameDir>/schematics` assumptions |
| Local history (`SyncHistory` / `CommitGraph`) | `dev.vitruvius.editor.sync.history.*` & `HistoryWindow` | `dev.liskov.core.vcs.VcsStore` + `LiskovExplorer` Inspector | Snapshot on `ProjectManager.save`; subscribe to `LiskovReloadBus` |
| Syncmatica multi-player project sharing | `dev.vitruvius.editor.sync.SyncService` | `LiskovSyncClient`, `LiskovServerStore`, `AclConfig`, `LockGuard` | Replace Syncmatica transport or gate behind `LiskovSyncClient.activeServerId()` |
| `vitruvius-editor.mixins.json` | `CameraMixin`, `EntityRenderDispatcherMixin`, `GuiCrosshairMixin`, `LevelRendererOutlineMixin`, `MouseHandlerGrabMixin` | Keep Vitruvius-specific 3D world/camera mixins; retire `MouseHandlerGrabMixin` once `LiskovUiHost` adds mouse-grab blocking (`P0`) | Keep gameplay mixins; move grab control to Liskov |

---

## 2. Gradle & `fabric.mod.json` Changes

### `settings.gradle` & `build.gradle`

1. Publish Liskov locally (`.\build.bat publishToMavenLocal` in `f:\workflow\Liskov`) or attach via `includeBuild('../Liskov')`.
2. In `f:\workflow\Vitruvius\settings.gradle`, remove `include 'ui-host'`.
3. In `f:\workflow\Vitruvius\build.gradle`, replace `:ui-host` with Liskov:

```groovy
// Before (Vitruvius/build.gradle)
implementation project(":ui-host")
include project(":ui-host")
compileOnly "io.github.spair:imgui-java-binding:1.90.0"

// After
repositories {
    mavenLocal()
    mavenCentral()
    // ... existing Modrinth repo
}
dependencies {
    // ... minecraft, fabric-loader, fabric-api, litematica, malilib
    modImplementation "dev.liskov:liskov:${project.liskov_version}"
    // Or explicit modular dependencies:
    // implementation "dev.liskov:liskov-core:${project.liskov_version}"
    // modImplementation "dev.liskov:liskov-net:${project.liskov_version}"
    // modImplementation "dev.liskov:liskov-ui:${project.liskov_version}"
}
```

### `src/main/resources/fabric.mod.json`

Replace `"cg-uihost": "*"` with `"liskov": ">=0.1.0"`:

```json
  "depends": {
    "fabricloader": ">=0.20.1",
    "fabric-api": "*",
    "minecraft": "${minecraft_dep}",
    "liskov": ">=0.1.0",
    "litematica": "*",
    "malilib": "*",
    "java": ">=25"
  }
```

---

## 3. ImGui Host, Mixins, Theme & Widget Cutover

### Host & Layer Registration (`EditorMode` & `EditorInputLayer`)

`dev.cg.uihost.UiHost` -> `dev.liskov.ui.host.LiskovUiHost`, and `dev.cg.uihost.UiLayer` -> `dev.liskov.ui.host.UiLayer`.

```java
// Before (dev.vitruvius.editor.EditorMode)
import dev.cg.uihost.UiHost;
...
UiHost.register(new EditorInputLayer());
UiHost.captureCursor(false);

// After
import dev.liskov.ui.host.LiskovUiHost;
...
LiskovUiHost.register(new EditorInputLayer());
LiskovUiHost.captureCursor(false);
```

In `dev.vitruvius.editor.EditorInputLayer`:
- Update `implements dev.cg.uihost.UiLayer` -> `implements dev.liskov.ui.host.UiLayer`.
- **Important `UiLayer` behavior difference:** In `Liskov` `HostCore.onKey`, returning `wantsKey() == true` dispatches `onKey(...)` to **all** visible layers without short-circuiting. Inside `EditorInputLayer.onKey(...)`, guard against `FilePickerModal.isOpen() || MubWidgets.CommandPalette.isOpen() || LiskovExplorer.get().visible()` until `HostCore` implements consumption short-circuiting (`P0` in [Gaps & Follow-ups](file:///f:/workflow/Liskov/docs/GAPS_AND_FOLLOWUPS.md)).
- **Mouse-grab mixin (`MouseHandlerGrabMixin`):** `dev.vitruvius.editor.mixin.MouseHandlerGrabMixin` prevents Minecraft from re-grabbing the cursor while `EditorMode.isActive()` is true. Since Liskov **`0.2.0+mc26.3`**, `UiLayer.blocksMouseGrab()` and `MouseHandlerMixin` (`grabMouse` + `turnPlayer`) handle this centrally — override `blocksMouseGrab() { return EditorMode.isActive(); }` on `EditorInputLayer` and drop `MouseHandlerGrabMixin`.

### Theme & Texture Lookup (`Ui`, `EditorChrome`, `BlockIcons`)

- Replace `dev.cg.uihost.Theme` references with `dev.liskov.ui.theme.MubTheme` and `dev.liskov.ui.theme.MubWidgets`.
- Note `MubTheme` exposes `MubTheme.colorFor(SemanticColor)`, `MubTheme.u32(int rgb)` / `u32(int rgb, float alpha)`, `MubTheme.textColored(...)`, and font getters `MubTheme.ui()`, `small()`, `mono()`, `monoS()`, `uiBold()`, `numM()`, `numL()`.
- **Texture registration (`BlockIcons`):** Vitruvius currently passes a lookup lambda into `UiHost.setTextureLookup(...)` with a fixed texture ID. Because `LiskovUiHost.setTextureLookup(...)` is a **single static slot** shared with all mods (such as Manhattan's `ItemIcons`), migrate `BlockIcons` to `LiskovUiHost.registerTexture(GpuTextureView)` (returns a unique monotonic `long` ID) and `LiskovUiHost.unregisterTexture(long)` on close/reload.

---

## 4. Project & Schematic I/O Cutover (`LiskovFs`, `VcsStore`, `LiskovReloadBus`)

### Namespace & Paths (`LiskovNamespace`, `ProjectLayout`, `Folders`, `ProjectManager`)

Liskov registers two namespaces Vitruvius cares about by default:
- `LiskovFs.get().schematicsDir()` (`<gameDir>/liskov/local/schematics/`, badge `"Lm"`, extensions `.litematic`, `.vproj`, `.lasm`)
- `LiskovFs.get().vitruviusDir()` (`<gameDir>/liskov/local/vitruvius/`, badge `"Vp"`, extensions `.vproj`, `.lasm`)

`LiskovMigration.migrateLegacyOnce` copies:
1. `<gameDir>/schematics/**` -> `<gameDir>/liskov/local/schematics/**`, renaming `project.json` -> **`project.vproj`**.
2. `<gameDir>/config/vitruvius/**` -> `<gameDir>/liskov/local/vitruvius/config/**`.

**Required Vitruvius cutover changes:**

1. **`ProjectLayout` (`dev.vitruvius.editor.io.ProjectLayout`):**
   Support `.vproj` as the canonical project manifest while keeping `project.json` fallback for unmigrated directories:
   ```java
   public static final String PROJECT_FILE = "project.vproj";
   public static final String LEGACY_PROJECT_FILE = "project.json";

   public static Path resolveProjectFile(Path projectDir) {
       Path vproj = projectDir.resolve(PROJECT_FILE);
       if (Files.isRegularFile(vproj)) return vproj;
       Path legacy = projectDir.resolve(LEGACY_PROJECT_FILE);
       return Files.isRegularFile(legacy) ? legacy : vproj;
   }
   ```
2. **`ProjectManager.Env` / `Reconciler` / `Folders`:**
   Resolve Vitruvius's config folder (`editor-session.json`, `editor-loose/<world>.lasm`, `folders.json`, shape presets) from `LiskovFs.get().vitruviusDir().resolve("config")` instead of `FabricLoader.getInstance().getConfigDir().resolve("vitruvius")` when `LiskovFs.isInitialized()` is true.
   Resolve schematics root via `LiskovFs.get().schematicsDir()` (also matched automatically by `LitematicaRedirectMixin` for Litematica's `DataManager`).

### Auto-Commit on Save (`VcsStore`) & Live Reload (`LiskovReloadBus`)

Replace Vitruvius's `dev.vitruvius.editor.sync.history.CommitGraph` / `Retention` on project save with `VcsStore` snapshots (which automatically deduplicate identical autosaves, auto-prune old autosaves keeping milestones & branch tips via `DEFAULT_AUTO_MAX_AUTOSAVES = 25` / `DEFAULT_AUTO_MAX_AGE_DAYS = 30`, and run `gcUnreferencedObjects()`), and subscribe `ProjectManager` to `LiskovReloadBus`:

```java
// On ProjectManager.save(ProjectNode project) — available since Liskov 0.2.0+mc26.3
if (LiskovFs.isInitialized()) {
    Path projDir = /* resolved project folder */;
    Path projFile = projDir.resolve("project.vproj");
    String relPath = LiskovFs.get().schematicsDir().relativize(projFile).toString().replace('\\', '/');

    var mc = Minecraft.getInstance();
    String uuid = mc.player != null ? mc.player.getStringUUID() : "";
    String name = mc.player != null ? mc.player.getName().getString() : "Local";

    VcsStore vcs = new VcsStore(LiskovFs.get().vcsRootDir());
    vcs.setAutoRetention(25, 30, 15); // optional: default is already 25 autosaves, 30d, keep >= 15

    if (LiskovDrives.LOCAL_DRIVE_ID.equals(activeDriveId)) {
        vcs.snapshotDirectory(
                LiskovDrives.LOCAL_DRIVE_ID,
                VcsStore.repoIdFor(relPath),
                projDir, uuid, name,
                autoSave ? "Autosave" : "Save project",
                false, autoSave
        );
    } else {
        // Snapshots locally (with auto-dedup/prune) AND uploads deltas to the active server drive:
        LiskovSyncClient.get().publishPath(
                null, projDir, relPath, FileTypeRegistry.VPROJ.id(),
                uuid, name, autoSave ? "Autosave" : "Save project", autoSave
        );
    }
}
```

And subscribe once during client init:

```java
LiskovReloadBus.subscribe(new LiskovReloadBus.Listener() {
    @Override
    public void onReload(LiskovReloadBus.ReloadNotice notice) {
        if (notice.relativePath().endsWith(".vproj") || notice.relativePath().endsWith(".lasm")) {
            // Queue reload of affected open ProjectNode on client thread
        }
    }

    @Override
    public void onConflict(LiskovReloadBus.ConflictNotice conflict) {
        // Surface conflict toast / resolution prompt in Vitruvius editor
    }
});
```

---

## 5. Shifting Sharing, Access & Locking to Liskov

Today `dev.vitruvius.editor.sync.SyncService` serializes projects as Syncmatica placements (`[LA2] <project>` + `SyncManifest` + `SyncPlan`). Moving onto Liskov `0.2.0+mc26.3` eliminates Syncmatica's 1x1x1 dummy placement hack:

- **Transport (`0.2.0+mc26.3` Ready):** Replace `SyncService` push/poll with `LiskovSyncClient.get().publishPath(...)` / `pushCommit(...)`, `requestDownload(...)`, `requestHistory(repoId, callback)`, `requestDelete(...)`, `requestRename(...)`, and `requestFolderMutate(...)` over `liskov:sync`.
- **Dirty/open tracking:** Call `LiskovSyncClient.get().setOpen(repoId, true)` when opening a project from a server drive (`DriveMount.Kind.SERVER_ONLINE`), and `setDirty(repoId, true/false)` when `EditorSession` has unsaved changes so peer pushes trigger `ConflictNotice` instead of clobbering local work.
- **ACLs & Locks:** Delegate folder/repo permissions to `LiskovServerStore` (`AclConfig` / `AccessEvaluator`: `VIEW`, `EDIT`, `ADMIN`, mutated client-side via `LiskovSyncClient.get().requestAclMutate(...)` or OP `/liskov acl set`) and editing locks to `LiskovSyncClient.get().requestLockToggle(folderId, repoId, lock, reason, confirmUnlockText)` enforced server-side by `LockGuard`.

---

## 6. Registries & Shared UI Integration

### Registering Vitruvius Actions & Status Badges (`FileActionRegistry`)

```java
FileActionRegistry.registerAction(new FileActionRegistry.Action(
        "vitruvius.open_editor",
        "OPEN IN VITRUVIUS",
        SemanticColor.FOCUS,
        ft -> ft.id().equals(FileTypeRegistry.VPROJ.id())
                || ft.id().equals(FileTypeRegistry.LASM.id())
                || ft.id().equals(FileTypeRegistry.LITEMATIC.id()),
        path -> {
            // Open path in ProjectManager / EditorMode
        }
));
```

### Replacing Schematic File Pickers (`FilePickerModal`)

```java
FilePickerModal.openPicker(
        new FilePickerModal.Request(
                "Select Schematic / Project",
                Set.of(FileTypeRegistry.LITEMATIC.id(), FileTypeRegistry.VPROJ.id(), FileTypeRegistry.LASM.id()),
                LiskovFs.get().schematicsDir()
        ),
        pickedPath -> {
            // Attach schematic or open assembly
        }
);
```

### Title & Pause Screen Overview Card (`MenuOverviewRegistry`)

```java
MenuOverviewRegistry.get().register(new MenuOverviewRegistry.CardProvider() {
    @Override public String id() { return "vitruvius.overview"; }
    @Override public String title() { return "VITRUVIUS EDITOR"; }
    @Override public SemanticColor accentRole() { return SemanticColor.FOCUS; }
    @Override public int order() { return 20; }
    @Override public boolean supports(MenuOverviewRegistry.Surface s) {
        return s == MenuOverviewRegistry.Surface.TITLE_SCREEN || s == MenuOverviewRegistry.Surface.PAUSE_MENU;
    }
    @Override public void drawCardBody(MenuOverviewRegistry.Surface s) {
        MubTheme.textColored(MubTheme.DIM, "Open projects: " + openProjectCount());
        if (MubWidgets.termButton("TOGGLE EDITOR (F6)", SemanticColor.FOCUS)) {
            EditorMode.toggle(Minecraft.getInstance());
        }
    }
});
```

---

## 7. Ordered Cutover Checklist & Verification

1. **Gradle & `fabric.mod.json`:** Replace `:ui-host` with `dev.liskov:liskov` in `Vitruvius/build.gradle` and `fabric.mod.json`; remove `include 'ui-host'` from `settings.gradle`.
2. **Host & Theme Imports:** Switch `dev.cg.uihost.{UiHost, UiLayer, Theme}` -> `dev.liskov.ui.host.{LiskovUiHost, UiLayer}` and `dev.liskov.ui.theme.{MubTheme, MubWidgets}`. Switch `BlockIcons` to `LiskovUiHost.registerTexture`. Keep `MouseHandlerGrabMixin` until Liskov `v0.1.1` lands `blocksMouseGrab()`.
3. **Paths & `.vproj` Support:** Update `ProjectLayout` to read/write `project.vproj` (with `project.json` fallback) and resolve Vitruvius config/schematics roots via `LiskovFs.get()` (`vitruviusDir().resolve("config")` and `schematicsDir()`).
4. **VCS & Reload Bus:** Snapshot project bundles (`project.vproj` + `files/**`) into `VcsStore` on save and subscribe `ProjectManager` to `LiskovReloadBus`.
5. **Explorer & Menu Integrations:** Register `FileActionRegistry` actions, `CommandPalette` entries (e.g. Toggle Editor Mode `F6`), `MenuOverviewRegistry` card, and `FilePickerModal` schematic selection.
6. **Sync Cutover:** Gate/replace `SyncService` behind `LiskovSyncClient` (`liskov:sync`) once `v0.1.1` fixes `LiskovSyncClient` multi-file bundle push & history responses (`P0`).
7. **Verification:**
   - Run Vitruvius headless tests (`gradlew test`) covering `dev.vitruvius.shape` and `ProjectManager` with `.vproj` + `project.json` fixtures.
   - Launch client with `liskov-0.1.0+mc26.3.jar` + Vitruvius: verify `liskov/.migrated` preserves legacy projects, `F6` opens Vitruvius Editor without breaking `F8` (`LiskovExplorer`), `MubTheme.cyclePreset()` updates Vitruvius windows live, and saving creates a commit visible in `LiskovExplorer` -> `InspectorPane`.
