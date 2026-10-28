# Jm2Bm

[![CI](https://github.com/Crispy-Monke-eSports/Jm2Bm/actions/workflows/ci.yml/badge.svg)](https://github.com/Crispy-Monke-eSports/Jm2Bm/actions/workflows/ci.yml)
[![GitHub release](https://img.shields.io/github/v/release/Crispy-Monke-eSports/Jm2Bm)](https://github.com/Crispy-Monke-eSports/Jm2Bm/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Jm2Bm is a dedicated-server mod for Minecraft Java Edition 26.3. The same universal JAR supports
both Fabric and NeoForge. It keeps selected, server-managed JourneyMap waypoint groups synchronized
into existing BlueMap marker sets. JourneyMap is always the source of truth.

> **Preparing for Minecraft 26.4:** The universal build remains on the verified 26.3
> dependencies until both loaders and the required JourneyMap API/runtime are available
> for 26.4. See [the 26.4 migration checklist](docs/minecraft-26.4.md). Do not install
> the current 26.3 JAR on a 26.4 server.

## Requirements

- Java 25
- Fabric Loader 0.19.5 or newer and Fabric API 0.165.0+26.3, **or** NeoForge 26.3.0.98 or newer
- The matching Fabric or NeoForge build of JourneyMap 26.3-6.0.10 or newer compatible 6.0.x build
- The matching Fabric or NeoForge build of BlueMap 5.x with BlueMap API 2.8.x compatibility

Install the same Jm2Bm JAR together with the loader-matching JourneyMap and BlueMap JARs in the
server's `mods/` directory. Fabric servers also need Fabric API. No Jm2Bm client mod is needed, and
Fabric and NeoForge dependencies must not be mixed on one server.

## Configuration

The first start creates `config/jm2bm/config.yaml` and
`config/jm2bm/icons/default.svg`. The initial mapping list is deliberately empty so a first
start can never take ownership of, or clear, an existing BlueMap marker set.

Example:

```yaml
syncOnStartup: true
defaultIcon: default.svg
mappings:
  - journeyMapGroupUuid: 550e8400-e29b-41d4-a716-446655440000
    blueMapMarkerSet: towns
    icon: town.svg
  - journeyMapGroupUuid: 123e4567-e89b-42d3-a456-426614174000
    blueMapMarkerSet: towns
    icon: dungeon.svg
```

Put PNG or SVG icons below `config/jm2bm/icons/`. Icon paths cannot be absolute or leave that
directory. Marker set IDs are case-sensitive and must already exist on at least one BlueMap map.
A map is applicable to a marker set when that set ID exists on the map; the set need not exist on
every dimension's map. Jm2Bm never creates marker sets or changes their labels and display settings.

Two or more JourneyMap groups may target the same marker set. Their waypoints are reconciled as
one union, while each group retains its configured icon. Each waypoint's permanent JourneyMap
GUID is used as the BlueMap marker ID.

> **Ownership warning:** A marker set named by an active mapping is wholly owned by Jm2Bm on
> every applicable map. After a successful snapshot, all markers not represented by the mapped
> JourneyMap groups are removed, including manually created markers.

## Commands

- `/jm2bm` or `/jm2bm help` shows the color-coded command guide; its command names are clickable
  and TAB completes available groups, marker sets, and icons.
- `/jm2bm link <journeymap-global-group> <bluemap-marker-set>` links a group and set by their
  friendly display names. TAB completion lists all live JourneyMap global groups and BlueMap sets.
  Omitting the marker-set argument reports the group's current links without changing them.
  If the selected set already contains markers, the command asks whether to clear it and continue
  or cancel. This safeguard applies to the in-game command, not mappings entered in `config.yaml`.
- `/jm2bm unlink <journeymap-global-group> <bluemap-marker-set>` removes a link. TAB completion
  only lists linked groups, then only sets linked to the selected group.
- `/jm2bm icon <bluemap-marker-set> <icon-file>` assigns an existing PNG/SVG from
  `config/jm2bm/icons/` to every active link targeting that set. Both arguments support TAB
  completion, and the change is saved and reconciled immediately. Omitting the icon argument
  reports the icon currently in use.
- `/jm2bm list` lists every active link using friendly names, with stored identifiers as fallback.
- `/jm2bm reload` validates and activates `config.yaml`, republishes changed icons, reconciles all
  destinations, and cleans a marker set once when its final mapping was removed.
- `/jm2bm status` reports API connectivity, mappings, destination counts, missing groups/sets,
  and the latest sync result.

All commands require the vanilla gamemaster permission (operator level 2 equivalent). `link`,
`unlink`, and `icon` save immediately; friendly names are resolved live, while `config.yaml` stores
the stable JourneyMap UUID and BlueMap marker-set ID. Each mutation first validates and uses the
current YAML file as its source of truth. Names containing spaces are inserted as quoted arguments.
An invalid reload leaves the last valid in-memory configuration active and does not trigger a
reconciliation.

## Safety behavior

Jm2Bm obtains and validates the complete relevant global JourneyMap snapshot before publishing
or removing any BlueMap markers. A missing API, failed read, incomplete waypoint, conflicting
GUID, or icon-publication failure aborts marker mutation. A mapped group that is absent from a
successfully validated complete group snapshot is treated as empty, as required by the
specification.

Synchronization is event-driven. It runs for JourneyMap global waypoint/group events, server
startup (when enabled), every BlueMap enable/reload, and `/jm2bm reload`; there is no polling.

## Build

```bash
./gradlew clean test build
```

The universal distributable JAR is written to `build/libs/`. It contains both loader manifests and
small loader-specific entrypoints around one shared implementation. JourneyMap and BlueMap API
classes are not bundled. The runtime libraries `flow-math` and SnakeYAML are nested with metadata
for both loaders so NeoForge can safely deduplicate them.

## Contributing and releases

Pull requests build and verify the universal Fabric/NeoForge JAR on Java 25 with GitHub Actions. See [CONTRIBUTING.md](CONTRIBUTING.md)
for the development and safety requirements and [SECURITY.md](SECURITY.md) for private security
reporting guidance.

Maintainers publish a release by pushing a semantic-version tag such as `v1.0.1`. The release
workflow derives the mod version from the tag and requires every test and verification check to
pass before publishing to either destination. It then uploads the binary JAR, sources JAR, and
SHA-256 checksums to GitHub Releases, followed by the universal binary JAR to CurseForge as a
Fabric/NeoForge file for the stable Minecraft version in `gradle.properties`. Configure the repository secret `CURSEFORGE_TOKEN` with a
CurseForge author API token and the repository variable `CURSEFORGE_PROJECT_ID` with the project's
numeric ID before publishing a release.
