# Liskov Developer & Migration Documentation

**Target baseline:** Liskov `0.1.0+mc26.3` (Minecraft `26.3`, Fabric Loader `>=0.20.1`, Fabric API `0.166.2+26.3`, Java `25`, Dear ImGui `1.90.0`).

Liskov unifies three cross-mod pillars for the ModsUndBots ecosystem (Vitruvius, Manhattan, Heavymatica, and future Fabric mods):

1. **`:liskov-core`** (`dev.liskov.core`) — Pure Java 25 + Gson library (zero Minecraft or ImGui dependencies) providing the Drives filesystem (`local` + server mounts), non-destructive legacy migration, `.liskov-vcs/` content-addressed version control with 3-way merge, ACLs and lock guards, folder catalogs, favorites/trash, reload watching, and a pure-Java `.litematic` reader.
2. **`:liskov-net`** (`dev.liskov.net`) — Common client + server Fabric mod layer registering the `liskov:sync` payload channel, server-side repository store (`<serverWorldRoot>/liskov/`), `LiskovSyncClient`, `LiskovReloadBus`, and `/liskov` commands.
3. **`:liskov-ui`** (`dev.liskov.ui`) — Client-only Fabric mod layer hosting a single shared Dear ImGui context (`LiskovUiHost` / `UiLayer`), ModsUndBots theme presets (`MubTheme`), shared widgets (`MubWidgets`, `ToastHost`, `CommandPalette`), Title/Pause screen overview cards (`MenuOverviewRegistry`), 3D schematic preview (`SchematicPreview3D`), and the multi-window `LiskovExplorer` + `FilePickerModal`.

---

## Documentation Index & Recommended Reading Order

| Order | Document | Audience & Purpose |
| --- | --- | --- |
| 1 | [System Architecture](file:///f:/workflow/Liskov/docs/ARCHITECTURE.md) | **Start here.** Explains the 3-module split, Jar-in-Jar packaging, Gradle consumption patterns, Drives layout, `.liskov-vcs/`, `liskov:sync` wire protocol, ACL/lock enforcement, and the shared `LiskovUiHost` / `MubTheme` / `LiskovExplorer` architecture. |
| 2 | [API Reference](file:///f:/workflow/Liskov/docs/API_REFERENCE.md) | Verified class-by-class reference across `:liskov-core`, `:liskov-net`, and `:liskov-ui` with exact packages, signatures, constants, thread-safety/lifecycle notes, and Java snippets. |
| 3 | [Migrating Vitruvius](file:///f:/workflow/Liskov/docs/MIGRATING_VITRUVIUS.md) | File-by-file transition plan for `Vitruvius`: retiring `cg-uihost` (`ui-host/`), routing project/schematic I/O through `LiskovFs` (`VITRUVIUS`) and `VcsStore`, adopting `LiskovSyncClient`, and registering in `FileActionRegistry` / `MenuOverviewRegistry`. |
| 4 | [Migrating Manhattan](file:///f:/workflow/Liskov/docs/MIGRATING_MANHATTAN.md) | File-by-file transition plan for `Manhattan`: retiring duplicate `ImGuiManager` / `ImGuiRenderPearlRenderer` / `ImGuiNatives` / `UiTheme`, migrating `<gameDir>/manhattan*` and `scripts/`, mapping fleet/shared folders onto `LiskovServerStore`, and integrating `CommandPalette` / `ToastHost`. |
| 5 | [Gaps & Follow-ups](file:///f:/workflow/Liskov/docs/GAPS_AND_FOLLOWUPS.md) | **Read before cutting over.** Prioritized (`P0` / `P1` / `P2`) engineering audit of hooks, keycode/input mismatches, and sync endpoints missing in `0.1.0+mc26.3` that should land in Liskov `v0.1.1` before or alongside full migration. |

---

## Quick Reading Tracks

- **Building a new Fabric mod on Liskov:** Read [System Architecture](file:///f:/workflow/Liskov/docs/ARCHITECTURE.md) -> [API Reference](file:///f:/workflow/Liskov/docs/API_REFERENCE.md).
- **Migrating Vitruvius:** Read [System Architecture](file:///f:/workflow/Liskov/docs/ARCHITECTURE.md) -> [Gaps & Follow-ups](file:///f:/workflow/Liskov/docs/GAPS_AND_FOLLOWUPS.md) -> [Migrating Vitruvius](file:///f:/workflow/Liskov/docs/MIGRATING_VITRUVIUS.md).
- **Migrating Manhattan:** Read [System Architecture](file:///f:/workflow/Liskov/docs/ARCHITECTURE.md) -> [Gaps & Follow-ups](file:///f:/workflow/Liskov/docs/GAPS_AND_FOLLOWUPS.md) -> [Migrating Manhattan](file:///f:/workflow/Liskov/docs/MIGRATING_MANHATTAN.md).
- **Working on Liskov itself (`v0.1.1`):** Read [Gaps & Follow-ups](file:///f:/workflow/Liskov/docs/GAPS_AND_FOLLOWUPS.md) and run `build.bat test build` (Windows) or `./gradlew test build` (Linux/macOS, JDK 25).
