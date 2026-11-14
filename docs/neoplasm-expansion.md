# The Mass — Neoplasm Expansion

Target: Mindustry v8 build 160.7.

This branch is the integration branch for extending Project: Restoration's existing Neoplasm implementation. It deliberately preserves the current `classicMod.ClassicMod` loader and the existing `RBlocks` / `RUnitTypes` definitions. The host source was not overwritten.

## Primary baseline: Contagion Outpost Revamp v11

The user-provided `Project-Restoration-Contagion-Outpost-Revamp-v11-Neoplasm-Colony-Fixed.jar` is the primary functional and visual reference for this branch. It is a built Project: Restoration mod, not a generic example project.

- Mod version: `0.6.5-beta-neoplasm-sprite-update`
- Minimum game version: `160.7`
- Archive size: 48,086,607 bytes
- SHA-256: `9d1b51cbf8a1855b093dc11d30b7f75ac95dc0df8b9152dc782211bf1549ebe2`
- Contains 682 sprite files and 11 maps, including `maps/erekir/contagion-outpost.msav`

### Baseline implementations confirmed in the JAR

- Blocks: `CausticHeart`, `CausticCord`, `CausticDrill`, `CausticSpawner`, `CausticTurret`, `CausticCrafter`, `CausticMine`, and `NeoplasmBlock`.
- Colony/network managers: `NeoplasmCordNetwork` (vascular connectivity, pressure propagation, reconnect/repair queue), `NeoplasmGrowthLimiter`, and `NeoplasmPopulation`.
- AI/pathfinding: `NeoplasmAIController` (casualty/death memory and danger sampling), `NeoplasmGroundAI`, `NeoplasmFlyingAI`, `NeoplasmFlankerAI`, `NeoplasmArtilleryAI`, `NeoplasmSymbiontAI`, `NeoplasmHugAI`, `NeoplasmPerfMonitor`, and `PathfinderCustom`.
- `PathfinderCustom` exposes D4 target selection, dodge target selection, and separate core, steam-vent, and ore fields.
- Existing units include Blob, Pallium, Worm, Mule, Kalyx, Hydro Bomber, Miserable, and the additional Siege Crawler, Spore Carrier, Polyctenid, Scolopendra, and Mycelid types.
- The JAR includes the v8-prototype Neoplasm and unit sprite families.

### Reconciliation with repository source

The repository already has the core `RBlocks.java`, `RUnitTypes.java`, Caustic block classes, `NeoplasmAIController.java`, `NeoplasmFlyingAI.java`, and `PathfinderCustom.java`. The supplied v11 JAR contains additional compiled implementations not present as Java files on the current master source tree, including the network/growth/population managers and several AI variants listed above. Those need to be recovered/reimplemented and reviewed against the JAR behavior before calling the source tree equivalent.

The JAR is a compiled reference and asset baseline; it does not include Java source. Do not claim exact source recovery from it. Keep its content IDs, balance, visuals, and working behavior as the baseline where verifiable. New ideas from the larger expansion brief should be implemented as additive extensions, not as a replacement for the existing colony system.

## Existing foundations

- `src/classicMod/content/RBlocks.java`: Neoplasm cord, Heart, turrets, spawners, mine and drill.
- `src/classicMod/content/RUnitTypes.java`: existing Pallium, Blob, Worm, Mule, Squid, Miserable and related units.
- `src/classicMod/ClassicMod.java`: original content-loading entry point.

## Integration rules

1. Reuse existing canonical unit types where they exist (notably Pallium and engine Renale); do not register duplicate content names.
2. Add new structures with `mass-` names and sandbox-only build visibility.
3. Keep the native Neoplastic team and native Neoplasm liquid behavior.
4. Add expansion content after the existing `RUnitTypes.load()` and `RBlocks.load()` calls.
5. Register world events from `ClassicMod.init()`, guarded against duplicate initialization.
6. Keep all changes on `neoplasm-expansion` until the v160.7 Actions build passes.

## Settings intended for the integration

- `mass-enable-removed-latum`: optional Latum and argent-chitin worm types.
- `neoplasmDebugAI`: casualty quadtree / flow-field debug overlay.
- `mass-max-blobs-per-heart`: configurable per-Heart Blob population cap.

## Build

The repository's existing Gradle wrapper and Android deploy workflow target Mindustry v160.7. Run `./gradlew clean deploy` in GitHub Actions. A successful Gradle build verifies API compilation; it does not replace in-game testing.

## Status

The integration branch has been created and the host loader is intentionally unchanged pending source installation. The standalone source package is supplied separately as `the-mass-neoplasm-source.zip`; the next integration step is to place its `themass` package under `src/themass`, reconcile the two canonical unit types, then add the loader calls and settings above.
