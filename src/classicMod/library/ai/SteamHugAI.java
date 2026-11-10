package classicMod.library.ai;

import arc.util.*;
import classicMod.content.*;
import classicMod.library.blocks.neoplasiaBlocks.CausticHeart;
import mindustry.Vars;
import mindustry.world.Tile;
import mindustry.world.meta.Attribute;

public class SteamHugAI extends NeoplasmAIController {

    boolean stucked = false;

    @Nullable
    Tile targetDestination;

    @Override
    public void updateMovement() {
        Tile closestVent = getClosestVent();
        if (closestVent != null){
            targetDestination = closestVent;
            pathfind(PathfinderCustom.fieldVent);
        } else {
            ignore = true;
            unit.kill();
        }
        faceMovement();
        Tile tile = unit.tileOn();
        if (tile != null && tile.floor().attributes.get(Attribute.steam) >= 1f && !(tile.build instanceof CausticHeart.HeartBuilding)) {
            ignore = true;
            tile.setBlock(RBlocks.cord, unit.team);
            unit.kill();
        }
    }

    @Override
    public void pathfind(int pathTarget) {
        stucked = false;
        // Share the improved stuck recovery and safe flowfield routing used by
        // the other Neoplasm ground units.
        super.pathfind(pathTarget);
    }
}
