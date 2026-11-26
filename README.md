# Liskov

Unified Minecraft Drives Filesystem, Hidden `.liskov-vcs/` Version Control, `liskov:sync` Server Sync & ACLs, 3D Schematic Previews, and Shared ModsUndBots ImGui UI Host.

## Modules

Liskov is structured as a three-module Gradle build packaged into a single universal Fabric mod JAR (`liskov-<version>.jar`) via Fabric Jar-in-Jar:

- **`:liskov-core`** (`dev.liskov.core`): Pure Java 25 + Gson library (zero Minecraft/ImGui dependencies). Implements the Drives filesystem (`LOCAL` + named server drives), non-destructive migration, folder catalogs, favorites/trash, reload watcher, pure-Java `.litematic` reader, `.liskov-vcs/` content-addressed version control, ACLs & locks, and sync protocol models.
- **`:liskov-net`** (`dev.liskov.net`): Common Client+Server Fabric mod layer registering the `liskov:sync` channel, server-side store under `<serverWorldRoot>/liskov/`, reload bus, and `/liskov` commands.
- **`:liskov-ui`** (`dev.liskov.ui`): Fabric Client Dear ImGui host (sharing a single ImGui context, ModsUndBots theme presets, widgets, Command Palette, Title/Pause Screen overview registry, 3D rotatable schematic preview, and multi-window Liskov Explorer), bundling `:liskov-core`, `:liskov-net`, and cross-platform ImGui natives.

## Requirements & Installation

- **Minecraft:** `26.3`
- **Fabric Loader:** `>=0.20.1`
- **Fabric API:** Latest for `26.3`
- **Java:** `25+`

Drop `liskov-0.2.4+mc26.3.jar` into the `mods/` directory on your Fabric client and/or server.

## Mod Suite

Liskov is the shared foundation (Drives filesystem, `.liskov-vcs/` version control, compressed/chunked `liskov:sync` networking, and single-context Dear ImGui host) for the Minecraft 26.3 mod suite:

| Mod | Role | Default Keybinds |
|---|---|---|
| **[Liskov](https://github.com/GermanCGArtist/Liskov)** | Shared ImGui UI host, Drives filesystem, File Explorer, Command Palette, VCS & server sync | `F8` Explorer · `Ctrl+P` Command Palette |
| **[Manhattan](https://github.com/GermanCGArtist/Manhattan)** | Automation scripting, Midtown path engine, docked workspace & multi-bot fleet client | `J` Workspace · `K` Capture · `Ctrl+Shift+P` Script Palette |
| **[Manhattan Fleet Server](https://github.com/GermanCGArtist/manhattan-fleet-server)** | Server-side multi-bot fleet coordinator for Manhattan | `/fleet` server commands |
| **[Vitruvius](https://github.com/GermanCGArtist/Vitruvius)** | Blender-style 3D schematic scene editor, projects, assemblies, arrays & shape generator | `F6` 3D Editor |
| **[Neurath](https://github.com/GermanCGArtist/Neurath)** | Octilinear/geographic transit network planner, JourneyMap vector overlay & Spectate mode | `N` Transit Diagram · `V` Spectate |

## Quick Start & Controls

- **`F8`**: Open / toggle the Liskov File Explorer (works from gameplay, Title/Pause screens, and mod UIs)
- **`Ctrl+P`**: Open the unified Liskov Command Palette (`Up`/`Down` to navigate, `Enter` to run, `Esc` to close)
- **Explorer Shortcuts**: `Ctrl+F` search · `Ctrl+N` new window · `Ctrl+Shift+N` new folder · `Ctrl+W` close window · `Ctrl+A`/`C`/`X`/`V` select/copy/cut/paste · `F2` rename · `F5`/`Ctrl+R` reload · `Ctrl+1/2/3` Columns/List/Grid · `Delete` trash · `Esc` close
- **File Picker**: `Up`/`Down` navigate · `Enter` or double-click to select · `Esc` cancel
- **Pass-through**: `F11` (fullscreen), `F1` (HUD), `F3` (debug), and vanilla screens (`ChatScreen`, `PauseScreen`, inventories) always interoperate cleanly, and closing overlays automatically re-grabs the mouse when returning to gameplay
- **`/liskov`** (server / OP commands): `status`, `share`, `acl`, `lock`, `unlock`, `history`, `revert`

## Building from Source

- **Windows:** `build.bat test build publishToMavenLocal` (automatically locates JDK 25 and sets a short `jdk.net.unixdomain.tmpdir`)
- **Linux / macOS (JDK 25):** `./gradlew test build publishToMavenLocal`

Output universal mod JAR: `liskov-ui/build/libs/liskov-<version>+mc26.3.jar`

## Documentation

- [Documentation Hub](docs/README.md) — Reading order and overview for mod authors and contributors
- [System Architecture](docs/ARCHITECTURE.md) — 3-module split, Jar-in-Jar packaging, Drives filesystem, `.liskov-vcs/`, `liskov:sync`, ACLs, and `LiskovUiHost`
- [API Reference](docs/API_REFERENCE.md) — Verified class-by-class reference for `:liskov-core`, `:liskov-net`, and `:liskov-ui`
- [Migrating Vitruvius](docs/MIGRATING_VITRUVIUS.md) — File-by-file transition guide for Vitruvius (`cg-uihost`, project/schematic I/O, sync, registries)
- [Migrating Manhattan](docs/MIGRATING_MANHATTAN.md) — File-by-file transition guide for Manhattan (ImGui host, `MubTheme`/`MubWidgets`, storage migration, fleet/sync)
- [Gaps & Follow-ups](docs/GAPS_AND_FOLLOWUPS.md) — Prioritized (`P0`/`P1`/`P2`) audit of missing hooks and follow-up items
