# Contributing to Jm2Bm

Thank you for contributing. Keep changes focused on the one-way, server-side
JourneyMap-to-BlueMap contract described in `Jm2Bm-specifikation.md`.

## Development setup

1. Install a Java 25 JDK.
2. Clone the repository.
3. Run `./gradlew clean build`.

The Gradle build downloads Minecraft, Fabric API, NeoForge, and compile-only API dependencies.
JourneyMap and BlueMap API classes must never be shaded into the distributable JAR. The universal
JAR intentionally nests only BlueMap's public `flow-math` transitive dependency, with loader
metadata that permits version deduplication.

## Pull requests

- Base work on `main` and keep each pull request narrowly scoped.
- Add tests for configuration validation and synchronization planning changes.
- Preserve the destructive-sync safety rule: an invalid or incomplete JourneyMap snapshot must
  never be interpreted as an empty source.
- Update `README.md` and `CHANGELOG.md` for user-visible behavior.
- Do not commit local server worlds, configs, logs, IDE state, downloaded mod JARs, or build output.

By contributing, you agree that your contribution is licensed under the repository's MIT license.
