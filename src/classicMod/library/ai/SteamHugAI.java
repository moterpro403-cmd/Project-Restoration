package classicMod.library.ai;

import classicMod.content.RBlocks;
import classicMod.library.blocks.neoplasiaBlocks.CausticHeart;
import mindustry.Vars;
import mindustry.world.Tile;
import mindustry.world.meta.Attribute;

public class SteamHugAI extends NeoplasmAIController {
    boolean stucked = false;

    @Override
    public void updateMovement() {
        Tile closestVent = getClosestVent();
        if (closestVent != null) {
            pathfind(PathfinderExtended.fieldVent);
        } else {
            ignore = true;
            unit.kill();
        }

        faceMovement();
        Tile tile = unit.tileOn();
        if (tile != null && tile.floor().attributes.get(Attribute.steam) >= 1f
                && !(tile.build instanceof CausticHeart.HeartBuilding)) {
            ignore = true;
            tile.setBlock(RBlocks.cord, unit.team);
            unit.kill();
        }
    }

    @Override
    public void pathfind(int pathTarget) {
        stucked = false;
        int costType = unit.type.flowfieldPathType;
        Tile tile = unit.tileOn();
        if (tile == null) {
            stucked = true;
            return;
        }

        // Use the legacy remembered-death tile avoidance before the normal vent field.
        if (applyDodgePathfind(pathTarget)) return;

        Tile targetTile = Vars.pathfinder.getTargetTile(
                tile, Vars.pathfinder.getField(unit.team, costType, pathTarget)
        );
        if (targetTile != null && tile != targetTile
                && (costType != 2 || (targetTile.floor() != null && targetTile.floor().isLiquid))) {
            unit.movePref(vec.trns(
                    unit.angleTo(targetTile.worldx(), targetTile.worldy()), unit.speed()
            ));
        } else {
            stucked = true;
        }
    }
}