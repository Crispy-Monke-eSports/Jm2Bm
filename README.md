# Jm2Bm

[![CI](https://github.com/Crispy-Monke-eSports/Jm2Bm/actions/workflows/ci.yml/badge.svg)](https://github.com/Crispy-Monke-eSports/Jm2Bm/actions/workflows/ci.yml)
[![GitHub release](https://img.shields.io/github/v/release/Crispy-Monke-eSports/Jm2Bm)](https://github.com/Crispy-Monke-eSports/Jm2Bm/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Jm2Bm is a dedicated-server mod for Minecraft Java Edition 26.3. The same universal JAR supports
both Fabric and NeoForge. It keeps selected, server-managed JourneyMap waypoint groups synchronized
into existing BlueMap marker sets. JourneyMap is always the source of truth.

## Requirements

- Java 25
- Fabric Loader 0.19.5 or newer and Fabric API 0.165.0+26.3, **or** NeoForge 26.3.0.98 or newer
- The matching Fabric or NeoForge build of JourneyMap 26.3-6.0.10 or newer compatible 6.0.x build
- The matching Fabric or NeoForge build of BlueMap 5.x with BlueMap API 2.8.x compatibility

Install the same Jm2Bm JAR together with the loader-matching JourneyMap and BlueMap JARs in the
server's `mods/` directory. Fabric servers also need Fabric API. No Jm2Bm client mod is needed, and
Fabric and NeoForge dependencies must not be mixed on one server.

## Configuration

The first start creates `config/jm2bm/config.json` and
`config/jm2bm/icons/default.svg`. The initial mapping list is deliberately empty so a first
start can never take ownership of, or clear, an existing BlueMap marker set.

Example:

```json
{
  "syncOnStartup": true,
  "defaultIcon": "default.svg",
  "mappings": [
    {
      "journeyMapGroupUuid": "550e8400-e29b-41d4-a716-446655440000",
      "blueMapMarkerSet": "towns",
      "icon": "town.svg"
    },
    {
      "journeyMapGroupUuid": "123e4567-e89b-42d3-a456-426614174000",
      "blueMapMarkerSet": "towns",
      "icon": "dungeon.svg"
    }
  ]
}
```

Put PNG or SVG icons below `config/jm2bm/icons/`. Icon paths cannot be absolute or leave that
directory. Marker set IDs are case-sensitive and must already exist on each applicable BlueMap
map. Jm2Bm never creates marker sets or changes their labels and display settings.

Two or more JourneyMap groups may target the same marker set. Their waypoints are reconciled as
one union, while each group retains its configured icon. Each waypoint's permanent JourneyMap
GUID is used as the BlueMap marker ID.

> **Ownership warning:** A marker set named by an active mapping is wholly owned by Jm2Bm on
> every applicable map. After a successful snapshot, all markers not represented by the mapped
> JourneyMap groups are removed, including manually created markers.

## Commands

- `/jm2bm reload` validates and activates `config.json`, republishes changed icons, reconciles all
  destinations, and cleans a marker set once when its final mapping was removed.
- `/jm2bm status` reports API connectivity, mappings, destination counts, missing groups/sets,
  and the latest sync result.

Both commands require the vanilla gamemaster permission (operator level 2 equivalent).
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
classes are not bundled. BlueMap's public `flow-math` transitive dependency is nested with metadata
for both loaders so NeoForge can safely deduplicate it.

## Contributing and releases

Pull requests build and verify the universal Fabric/NeoForge JAR on Java 25 with GitHub Actions. See [CONTRIBUTING.md](CONTRIBUTING.md)
for the development and safety requirements and [SECURITY.md](SECURITY.md) for private security
reporting guidance.

Maintainers publish a release by pushing a semantic-version tag such as `v1.0.1`. The release
workflow derives the mod version from the tag, runs the full build, and uploads the binary JAR,
sources JAR, and SHA-256 checksums to GitHub Releases.
