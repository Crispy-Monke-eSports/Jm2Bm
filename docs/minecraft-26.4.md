# Minecraft Java 26.4 migration checklist

Status (2026-10-28): **preparation only — not a 26.4-compatible build**.

Minecraft Java 26.4 has not been released as a stable version. Snapshot 6 was published
on 2026-10-27. Fabric API 0.165.1+26.4 exists for that snapshot, but this mod must
ship one JAR for **both** Fabric and NeoForge, and must use compatible JourneyMap
server APIs and runtime mods on both loaders. The JourneyMap API repository currently
publishes 26.3-2.0.0, not a 26.4 API. These early snapshots are not a safe target
for the shared release JAR.

## When the ecosystem is ready

1. Confirm the stable 26.4 Minecraft server and compatible Fabric Loader, Fabric API,
   NeoForge, NeoForm, JourneyMap (Fabric **and** NeoForge) and BlueMap binaries exist.
   Confirm the JourneyMap API coordinates for common, Fabric and NeoForge; do not
   assume the 26.3 API/runtime JARs work on 26.4.
2. Update these **together** in `gradle.properties`:
   `minecraft_version=26.4`, `fabric_loader_version`,
   `fabric_api_version`, `loom_version`, `moddev_version`,
   `neoforge_version`, `neoform_version`, `journeymap_api_version`
   and `journeymap_runtime_version`. Update `bluemap_api_version` only
   if required by the compatible BlueMap build. Keep Java 25 unless Mojang
   or the loader toolchains change their minimum runtime.
3. Fix any mapped Minecraft command/event API changes in
   `src/main/java/se/jesper/jm2bm/` and the loader entrypoints. Pay particular
   attention to `BridgeCommands`, `Jm2BmMod`, and the event registrations in
   `fabric/` and `neoforge/`. Update test expectations if command APIs change.
4. Run `./gradlew --no-daemon clean build` with Java 25. The root
   `verifyUniversalJar` task checks the Fabric and NeoForge dependency
   requirements embedded in the universal JAR against `gradle.properties`,
   and checks the nested libraries. Do not skip `:common:test`.
5. Smoke-test **both** dedicated servers separately with their matching
   JourneyMap and BlueMap runtime builds: first start with no mappings,
   successful startup synchronization, `/jm2bm status`, link/unlink/icon,
   config reload, JourneyMap waypoint/group changes, and BlueMap disable/re-enable.
   Verify that a failed/incomplete snapshot never deletes BlueMap markers and that
   only configured marker sets are reconciled.
6. Inspect the release workflow and a tagged pre-release. CurseForge's game version
   is read from `minecraft_version` at release time, instead of being hard-coded
   as 26.3. Snapshot targets are deliberately rejected for stable release tags.
   Merge only after CI and **both** manual loader smoke tests pass.

## Useful upstream references

- [Minecraft Java snapshot notes](https://feedback.minecraft.net/hc/en-us/sections/360002267532-Snapshot-Information-and-Changelogs)
- [Fabric API Maven versions](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/)
- [JourneyMap API Maven versions](https://maven.blamejared.com/info/journeymap/journeymap-api-common/)
- [NeoForge Maven](https://maven.neoforged.net/releases/net/neoforged/neoforge/)
