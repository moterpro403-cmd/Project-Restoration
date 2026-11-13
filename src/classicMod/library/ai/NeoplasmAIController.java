package classicMod.library.ai;

import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.*;
import classicMod.content.RVars;
import classicMod.library.GeometryPlus;
import classicMod.library.blocks.neoplasiaBlocks.CausticHeart;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.Units;
import mindustry.entities.units.AIController;
import mindustry.gen.Unit;
import mindustry.world.Tile;

public class NeoplasmAIController extends AIController {

    public Seq<Tile> DodgeTile = new Seq<>(); // death locations remembered by this controller
    public Seq<Unit> groups = new Seq<>();

    // Per-controller nearby-unit memory, matching the PC implementation.
    public ObjectSet<Unit> knownNeoplasms = new ObjectSet<>();
    public Seq<Unit> neoplasmGroup = new Seq<>();
    private final Seq<Unit> deadToRemove = new Seq<>();

    public boolean update;
    public boolean ignore;

    @Override
    public void updateUnit() {
        // Discover nearby units that use this same Neoplasm AI family.
        Unit neo = Units.closest(unit.team, unit.x, unit.y, u ->
            u != this.unit &&
            u.controller() instanceof NeoplasmAIController &&
            !knownNeoplasms.contains(u)
        );

        if (neo != null) {
            knownNeoplasms.add(neo);
            neoplasmGroup.add(neo);
        }

        // Remember the dead unit's tile and its eight neighbors. Units that
        // intentionally remove themselves after placing Cord are ignored.
        deadToRemove.clear();
        for (Unit neoplasm : neoplasmGroup) {
            if (neoplasm == null || !neoplasm.dead) continue;

            Tile deadTile = neoplasm.tileOn();
            if (deadTile != null && neoplasm.controller() instanceof NeoplasmAIController ai && !ai.ignore) {
                for (Point2 offset : GeometryPlus.d8plus) {
                    Tile danger = Vars.world.tile(deadTile.x + offset.x, deadTile.y + offset.y);
                    if (danger != null) DodgeTile.addUnique(danger);
                }
            }

            deadToRemove.add(neoplasm);
            knownNeoplasms.remove(neoplasm);
        }
        neoplasmGroup.removeAll(deadToRemove);
        deadToRemove.clear();

        super.updateUnit();
    }

    @Nullable
    public Tile getClosestVent() {
        Seq<Tile> avaliableVents = PathfinderExtended.SteamVents.copy()
            .removeAll(tile -> tile.build instanceof CausticHeart.HeartBuilding);
        Tile vent = Geometry.findClosest(this.unit.x, this.unit.y, avaliableVents);
        return (vent != null && !(vent.build instanceof CausticHeart.HeartBuilding)) ? vent : null;
    }

    @Nullable
    public Tile getClosestVent(boolean avoidDangerous) {
        return getClosestVent();
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

    /**
     * Returns a PC-style death-aware path step when the custom flowfield is
     * ready. Null means normal Mindustry pathfinding should remain in control;
     * the current tile means a danger zone exists but no safe step was found.
     */
    @Nullable
    public Tile getDodgeTarget(int pathTarget, Tile tile) {
        if (tile == null || DodgeTile.isEmpty() || RVars.pathfinderCustom == null) return null;

        var field = RVars.pathfinderCustom.getField(
            unit.team,
            unit.type.flowfieldPathType,
            pathTarget
        );
        if (field == null || !field.initialized || !field.hasTargets()) return null;

        Tile next = RVars.pathfinderCustom.getTargetTileDodge(tile, field, DodgeTile);
        if (next == null || next == tile) next = getEdgeEscapeTile();
        return next == null ? tile : next;
    }

    @Nullable
    public Tile getEdgeEscapeTile() {
        Seq<Tile> candidates = new Seq<>();

        for (Tile danger : DodgeTile) {
            if (danger == null) continue;
            for (Point2 offset : Geometry.d8) {
                Tile neighbor = Vars.world.tile(danger.x + offset.x, danger.y + offset.y);
                if (neighbor != null
                    && !DodgeTile.contains(neighbor)
                    && neighbor.block() == Blocks.air
                    && neighbor.floor() != null
                    && !neighbor.floor().isLiquid) {
                    candidates.add(neighbor);
                }
            }
        }

        if (candidates.isEmpty()) return null;
        return candidates.sort(tile1 -> tile1.dst(unit)).first();
    }

    @Override
    public void pathfind(int pathTarget) {
        int costType = this.unit.type.flowfieldPathType;
        Tile tile = this.unit.tileOn();
        if (tile == null) return;

        // Do not alter ordinary unit routing until a death has actually been
        // remembered and the custom flowfield is initialized.
        Tile dodgeTarget = getDodgeTarget(pathTarget, tile);
        if (dodgeTarget != null) {
            if (dodgeTarget != tile
                && (costType != 2 || (dodgeTarget.floor() != null && dodgeTarget.floor().isLiquid))) {
                this.unit.movePref(vec.trns(
                    this.unit.angleTo(dodgeTarget.worldx(), dodgeTarget.worldy()),
                    this.unit.speed()
                ));
            }
            return;
        }

        Tile targetTile = Vars.pathfinder.getTargetTile(
            tile,
            Vars.pathfinder.getField(this.unit.team, costType, pathTarget)
        );
        if (targetTile != null && tile != targetTile
            && (costType != 2 || (targetTile.floor() != null && targetTile.floor().isLiquid))) {
            if (DodgeTile.contains(targetTile)) targetTile = getClosestTarget(240);
            if (targetTile != null) {
                this.unit.movePref(vec.trns(
                    this.unit.angleTo(targetTile.worldx(), targetTile.worldy()),
                    this.unit.speed()
                ));
            }
        }
    }
}
