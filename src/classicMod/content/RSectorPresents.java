package classicMod.content;

import mindustry.content.*;
import mindustry.game.Team;
import mindustry.type.SectorPreset;

public class RSectorPresents extends SectorPresets {

    public static SectorPreset
    silverCrags
    ;

    public static void load() {
        // First campaign stronghold for the Neoplasm faction.
        // It is an attack objective, not a Crux wave-defense sector.
        silverCrags = new SectorPreset("silverCrags", Planets.serpulo, 25) {{
            captureWave = 0;
            difficulty = 3;
            alwaysUnlocked = true;
            showHidden = true;
            requireUnlock = true;
            // Use a normal campaign launch loadout instead of requiring the
            // destination map's stored resource list in the source core.
            overrideLaunchDefaults = true;
            allowLaunchLoadout = true;
            rules = rules -> {
                rules.waveTeam = Team.neoplastic;
                rules.waves = true;
                rules.attackMode = true;
                rules.winWave = 0;
                rules.spawns.clear();
            };
        }};
    }
}
