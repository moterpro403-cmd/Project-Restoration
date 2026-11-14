package classicMod.library.ai;

import classicMod.content.RBlocks;
import classicMod.content.RVars;
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
            ignoreDeathAvoidance = true;
            unit.kill();
        }

        faceMovement();
        Tile tile = unit.tileOn();
        if (tile != null && tile.floor().attributes.get(Attribute.steam) >= 1f
            && !(tile.build instanceof CausticHeart.HeartBuilding)) {
            ignoreDeathAvoidance = true;
            tile.setBlock(RBlocks.cord, unit.team);
            unit.kill();
        }
    }

    @Override
    public void pathfind(int pathTarget) {
        stucked = false;
        int costType = this.unit.type.flowfieldPathType;
        Tile tile = this.unit.tileOn();
        if (tile == null) {
            stucked = true;
            return;
        }

        // Prefer a safe death-aware step when one exists.
        Tile dodgeTarget = getDodgeTarget(pathTarget, tile);
        if (dodgeTarget != null
            && dodgeTarget != tile
            && (costType != 2 || (dodgeTarget.floor() != null && dodgeTarget.floor().isLiquid))) {
            this.unit.movePref(
                vec.trns(this.unit.angleTo(dodgeTarget.worldx(), dodgeTarget.worldy()), this.unit.speed())
            );
            return;
        }

        // No usable safe step: resume the original Mindustry 160.7 vent route.
        Tile targetTile = Vars.pathfinder.getTargetTile(
            tile,
            Vars.pathfinder.getField(this.unit.team, costType, pathTarget)
        );
        if (targetTile != null && tile != targetTile
            && (costType != 2 || (targetTile.floor() != null && targetTile.floor().isLiquid))) {
            this.unit.movePref(
                vec.trns(this.unit.angleTo(targetTile.worldx(), targetTile.worldy()), this.unit.speed())
            );
        } else {
            stucked = true;
        }
    }
}
