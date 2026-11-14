# The Mass — Neoplasm Expansion

Target: Mindustry v8 build 160.7.

This branch is the integration branch for extending Project: Restoration's existing Neoplasm implementation. It deliberately preserves the current `classicMod.ClassicMod` loader and the existing `RBlocks` / `RUnitTypes` definitions. The host source was not overwritten.

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
