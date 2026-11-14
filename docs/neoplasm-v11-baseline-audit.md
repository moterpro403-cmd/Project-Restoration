# Neoplasm v11 Baseline Audit

This document records the inspected contents of the user-supplied baseline JAR.

## Artifact

- File: `Project-Restoration-Contagion-Outpost-Revamp-v11-Neoplasm-Colony-Fixed.jar`
- Mod: `restored-mind`
- Version: `0.6.5-beta-neoplasm-sprite-update`
- Minimum game version: `160.7`
- Size: 48,086,607 bytes
- SHA-256: `9d1b51cbf8a1855b093dc11d30b7f75ac95dc0df8b9152dc782211bf1549ebe2`

## Confirmed compiled systems

### Colony blocks
`NeoplasmBlock`, `CausticHeart`, `CausticCord`, `CausticDrill`, `CausticSpawner`, `CausticTurret`, `CausticCrafter`, and `CausticMine`.

### Colony systems
`NeoplasmCordNetwork` exposes network init/update, pressure propagation, connection checks, reconnect requests, bounded repair processing, and clear/reset support. `NeoplasmGrowthLimiter` provides team-based expansion throttling. `NeoplasmPopulation` tracks team populations and per-type caps.

### AI and pathfinding
`NeoplasmAIController` provides per-team danger fields, unit-destroy memory, danger-risk sampling, nearby-danger avoidance, dodge-pathfind integration, ground pathfinding, air routing, and steam-vent targeting.

`PathfinderCustom` provides cached flowfields, D4 target methods, dodge-target methods, and distinct steam-vent, ore, and enemy-core target fields.

Additional compiled controllers: `NeoplasmGroundAI`, `NeoplasmFlyingAI`, `NeoplasmFlankerAI`, `NeoplasmArtilleryAI`, `NeoplasmSymbiontAI`, `NeoplasmHugAI`, and `NeoplasmPerfMonitor`.

### Unit roster visible in compiled registry
Blob, Matte, Squid, Worm, Mule, Kalyx, Hydro Bomber, Miserable, Pallium, Siege Crawler, Spore Carrier, Polyctenid, Scolopendra, and Mycelid.

### Art and map content
The archive contains 682 sprite entries and 11 maps. The Erekir map `contagion-outpost.msav` is included.

## Source-of-truth decision

Use this JAR for behavioral and visual comparison, plus the current repository Java source as the editable codebase. The JAR contains class files, not Java source. Its bytecode can be inspected, but exact original source should not be claimed. Implement additive requested features only after reconciling the baseline systems and preserving existing identifiers and balance.
