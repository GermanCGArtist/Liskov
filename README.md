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

Drop `liskov-0.1.0+mc26.3.jar` into the `mods/` directory on your Fabric client and/or server.

## Quick Start & Controls

- **`F8`**: Open / toggle the Liskov Explorer window
- **`Ctrl+P`**: Open the Liskov Command Palette
- **`/liskov`** (server / OP commands): `status`, `share`, `acl`, `lock`, `unlock`, `history`, `revert`

## Building from Source

- **Windows:** `build.bat test build` (automatically locates JDK 25 and sets a short `jdk.net.unixdomain.tmpdir`)
- **Linux / macOS (JDK 25):** `./gradlew test build`

Output universal mod JAR: `liskov-ui/build/libs/liskov-<version>.jar`

## Documentation

- [Documentation Hub](file:///f:/workflow/Liskov/docs/README.md) — Reading order and overview for mod authors and contributors
- [System Architecture](file:///f:/workflow/Liskov/docs/ARCHITECTURE.md) — 3-module split, Jar-in-Jar packaging, Drives filesystem, `.liskov-vcs/`, `liskov:sync`, ACLs, and `LiskovUiHost`
- [API Reference](file:///f:/workflow/Liskov/docs/API_REFERENCE.md) — Verified class-by-class reference for `:liskov-core`, `:liskov-net`, and `:liskov-ui`
- [Migrating Vitruvius](file:///f:/workflow/Liskov/docs/MIGRATING_VITRUVIUS.md) — File-by-file transition guide for Vitruvius (`cg-uihost`, project/schematic I/O, sync, registries)
- [Migrating Manhattan](file:///f:/workflow/Liskov/docs/MIGRATING_MANHATTAN.md) — File-by-file transition guide for Manhattan (ImGui host, `MubTheme`/`MubWidgets`, storage migration, fleet/sync)
- [Gaps & Follow-ups](file:///f:/workflow/Liskov/docs/GAPS_AND_FOLLOWUPS.md) — Prioritized (`P0`/`P1`/`P2`) audit of missing hooks and `v0.1.1` follow-up items
