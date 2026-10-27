# Changelog

All notable changes to Jm2Bm will be documented here. The project follows
[Semantic Versioning](https://semver.org/).

## Unreleased

### Changed

- Idempotent `link`, `unlink`, and `icon` commands report a neutral no-change result instead of an error.

### Added

- `/jm2bm icon <marker-set> <icon-file>` assigns an existing configured icon in game.
- `/jm2bm icon <marker-set>` and `/jm2bm link <group>` query the active icon and links.
- Structured, color-coded command output and clickable `/jm2bm help` guidance.

### Fixed

- Clickable help labels no longer underline the trailing argument-space inserted into chat.
- Successful status output no longer repeats `OK` on a second line.
- Confirmation links quote prefixed UUID and marker-set identifiers so Brigadier accepts them.
- Marker sets present on only some BlueMap maps no longer produce false missing-set errors.

## 1.1.0 - 2026-10-27

### Added

- Automatic CurseForge publishing through `Kira-NT/mc-publish` for every GitHub Release.
- An in-game confirmation prompt before linking a BlueMap marker set that already contains markers.
- One universal server JAR for both Fabric 26.3 and NeoForge 26.3.
- Loader-specific lifecycle and command entrypoints around the shared synchronization core.
- Production server smoke testing for both loaders with JourneyMap 6.0.10 and BlueMap 5.29.
- Friendly-name `/jm2bm link` and `/jm2bm unlink` commands with context-aware TAB completion,
  plus `/jm2bm list` for all active links.
- Immediate, atomic persistence of command changes to UUID-based `config.yaml`.

### Fixed

- JourneyMap built-in groups with textual IDs no longer abort synchronization or TAB completion.
- Dependency-ready startup events no longer queue the same synchronization twice.
- Fabric now loads the nested `flow-math` and SnakeYAML libraries without relying on other mods.
- Missing, null, misspelled, and unknown YAML fields are rejected before they can clear mappings.
- Stale mappings remain removable by stored UUID and marker-set ID when live objects are gone.
- Incomplete one-time cleanup remains pending until every destination is available and successful.
- Icons are scoped per group-to-marker-set mapping instead of overwriting each other by group UUID.
- Unexpected BlueMap runtime failures update sync status and preserve pending cleanup for retry.

## 1.0.0 - 2026-10-26

### Added

- Initial dedicated-server Fabric implementation for Minecraft 26.3.
- Event-driven synchronization of global JourneyMap waypoints to existing BlueMap marker sets.
- Safe full reconciliation, shared destination sets, multi-dimension support, and stale-marker cleanup.
- PNG/SVG icon publication with content-hashed asset names.
- `/jm2bm reload` and `/jm2bm status` administrator commands.
- Configuration validation and protection against destructive synchronization on incomplete data.

[Unreleased changes](https://github.com/Crispy-Monke-eSports/Jm2Bm/compare/v1.1.0...HEAD) ·
[1.1.0 release](https://github.com/Crispy-Monke-eSports/Jm2Bm/releases/tag/v1.1.0) ·
[1.0.0 release](https://github.com/Crispy-Monke-eSports/Jm2Bm/releases/tag/v1.0.0)
