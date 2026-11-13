package classicMod.library.ai;

import arc.math.geom.Geometry;
import arc.struct.Seq;
import arc.util.*;
import classicMod.library.blocks.neoplasiaBlocks.CausticHeart;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.Units;
import mindustry.entities.units.AIController;
import mindustry.gen.Unit;
import mindustry.world.Tile;

public class NeoplasmAIController extends AIController {

    public Seq<Tile> DodgeTile = new Seq<>(); // TODO thus

    public boolean update;
    // Compatibility flag used by the mod's specialized Neoplasm-derived controllers.
    public boolean ignore;

    @Override
    public void updateUnit() {
        
        Unit uNeo = Units.closest(unit.team, unit.x, unit.y, u -> u.controller() instanceof NeoplasmAIController);

        super.updateUnit();
    }

    @Nullable
    public Tile getClosestVent() {
        return getClosestVent(false);
    }

    @Nullable
    public Tile getClosestVent(boolean avoidDangerous) {
        Seq<Tile> avaliableVents = PathfinderExtended.SteamVents.copy()
            .removeAll(tile -> tile.build instanceof CausticHeart.HeartBuilding || tile.block() != Blocks.air);
        Tile vent = Geometry.findClosest(this.unit.x, this.unit.y, avaliableVents);
        if (vent == null) return null;
        if (avoidDangerous) {
            var enemy = Units.findEnemyTile(this.unit.team, vent.getX(), vent.getY(), 240f, building -> !building.dead);
            if (enemy != null) {
                avaliableVents.removeAll(tile -> tile.dst(enemy) <= 80f);
                vent = Geometry.findClosest(this.unit.x, this.unit.y, avaliableVents);
            }
        }
        return (vent != null && !(vent.build instanceof CausticHeart.HeartBuilding)) ? vent : null;
    }

    public Tile getClosestTarget(int range){
        int r = (range % 2 == 1) ? range + 1 : range;
        int mid = r/2;
        Seq<Tile> avaliableLand = new Seq<>();

        for (int y = -mid; y < r; y++){
            for (int x = -mid; x < r; x++){
                Tile tile = Vars.world.tile(unit.tileX() + x, unit.tileY() + y);
                if (
                        tile != null
                                && tile.block() == Blocks.air
                                && tile.floor() != null
                                && !tile.floor().isLiquid
                ) avaliableLand.add(tile);
            }
        }

        if (avaliableLand.size <= 0) return null;
        avaliableLand.sort(tile -> tile.dst(target));

        return avaliableLand.first();
    }

    
    // Compatibility hooks for specialized controllers. The original Neoplasm
    // controller keeps its original pathing behavior; these hooks let variants
    // opt into their own steering without forcing extra scans on every unit.
    protected boolean avoidNearbyDanger(Tile destination) {
        return false;
    }

    public void routeAir() {
        if (target == null) return;
        vec.set(target.getX() - unit.x, target.getY() - unit.y);
        if (!vec.isZero()) unit.movePref(vec.setLength(unit.speed()));
    }

    @Override
    public void pathfind(int pathTarget) {
        int costType = this.unit.type.flowfieldPathType;
        Tile tile = this.unit.tileOn();
        if (tile != null) {
            Tile targetTile = Vars.pathfinder.getTargetTile(tile, Vars.pathfinder.getField(this.unit.team, costType, pathTarget));
            if (tile != targetTile && (costType != 2 || targetTile.floor().isLiquid)) {
                if (DodgeTile.contains(targetTile)) targetTile = getClosestTarget(240);
                this.unit.movePref(vec.trns(this.unit.angleTo(targetTile.worldx(), targetTile.worldy()), this.unit.speed()));
            }
        }
    }
}
