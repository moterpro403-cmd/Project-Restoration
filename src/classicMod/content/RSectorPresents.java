package classicMod.content;

import mindustry.content.*;
import mindustry.type.SectorPreset;

public class RSectorPresents extends SectorPresets {

    public static SectorPreset
    silverCrags,
    contagionOutpost,
    viscousBasin,
    arterialRift,
    necroticCitadel
    ;

    public static void load() {
        silverCrags = new SectorPreset("silverCrags", Planets.serpulo, 25) {{
            captureWave = 15;
            difficulty = 3;
        }};

        // Custom maps are packaged under assets/maps/erekir using the matching preset names.
        // The four sector indices are unassigned by Mindustry v160.7's vanilla Erekir presets.
        contagionOutpost = new SectorPreset("contagion-outpost", Planets.erekir, 15) {{
            difficulty = 2;
        }};

        viscousBasin = new SectorPreset("viscous-basin", Planets.erekir, 22) {{
            difficulty = 5;
        }};

        arterialRift = new SectorPreset("arterial-rift", Planets.erekir, 31) {{
            difficulty = 7;
        }};

        necroticCitadel = new SectorPreset("necrotic-citadel", Planets.erekir, 45) {{
            difficulty = 9;
        }};
    }
}
