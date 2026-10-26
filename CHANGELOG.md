# Changelog

All notable changes to Jm2Bm will be documented here. The project follows
[Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- One universal server JAR for both Fabric 26.3 and NeoForge 26.3.
- Loader-specific lifecycle and command entrypoints around the shared synchronization core.
- Production server smoke testing for both loaders with JourneyMap 6.0.10 and BlueMap 5.29.
- Friendly-name `/jm2bm link` and `/jm2bm unlink` commands with context-aware TAB completion,
  plus `/jm2bm list` for all active links.
- Immediate, atomic persistence of command changes to UUID-based `config.yaml`.

## 1.0.0 - 2026-10-26

### Added

- Initial dedicated-server Fabric implementation for Minecraft 26.3.
- Event-driven synchronization of global JourneyMap waypoints to existing BlueMap marker sets.
- Safe full reconciliation, shared destination sets, multi-dimension support, and stale-marker cleanup.
- PNG/SVG icon publication with content-hashed asset names.
- `/jm2bm reload` and `/jm2bm status` administrator commands.
- Configuration validation and protection against destructive synchronization on incomplete data.

[Unreleased changes](https://github.com/Crispy-Monke-eSports/Jm2Bm/compare/v1.0.0...HEAD) ·
[1.0.0 release](https://github.com/Crispy-Monke-eSports/Jm2Bm/releases/tag/v1.0.0)
