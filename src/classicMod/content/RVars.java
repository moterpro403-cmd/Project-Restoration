package classicMod.content;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import classicMod.library.ai.*;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.type.Item;

public class RVars extends Vars {
    public static float ClassicBulletsMultiplier = 4f; //Classic intended use and not other stuff

    public static float MaximumRangeCommand = Float.MAX_VALUE; //Modifies the range
    public static String CommandOrigin = "attack"; //Public Command Center
    public static RallyAI.UnitState PublicState = RallyAI.UnitState.attack; //Public UnitState
    public static String empty = "restored-mind-nullTexture"; //empty

    public static boolean useNewSound = false;
    /** constructors id registered custom **/
    public static ObjectMap<String, Integer> idcMap = new ObjectMap<>();

    public static PathfinderCustom pathfinderCustom;

    /** Ores the Neoplasm drill is allowed to extract. */
    public static Seq<Item> CordCanDrill = new Seq<>(
            new Item[]{
                    Items.beryllium,
                    Items.tungsten,
                    Items.copper,
                    Items.lead,
                    Items.scrap,
                    Items.coal,
                    Items.titanium,
                    Items.thorium,
                    Items.sand
            }
    );

    /** Add ores whose Item objects are registered by this mod after RItems.load(). */
    public static void initDrillWhitelist() {
        CordCanDrill.add(RItems.silver, RItems.goldPowder);
    }

    @Override
    public void loadAsync() {
        super.loadAsync();
        init_RVars();
    }

    public static void init_RVars() {
        PathfinderExtended.preloadAddons();
        pathfinderCustom = new PathfinderCustom();
    }
}
