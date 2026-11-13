package classicMod.library.ai;

import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.*;
import classicMod.content.RVars;
import classicMod.content.RUnitTypes;
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

    /**
     * Updates this controller's nearby Neoplasm group and records deaths.
     * Kept separate from updateUnit() so vanilla-AI subclasses can use the
     * same memory without replacing their original controller behavior.
     */
    public void updateNeoplasmTracking() {
        if (unit == null) return;

        // Discover nearby units that use this same Neoplasm AI family.
        Unit neo = Units.closest(unit.team, unit.x, unit.y, u ->
            u != this.unit &&
            isNeoplasmUnit(u) &&
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
            boolean ignoredDeath = neoplasm.controller() instanceof NeoplasmAIController ai && ai.ignore;
            if (deadTile != null && !ignoredDeath) {
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
    }

    @Override
    public void updateUnit() {
        updateNeoplasmTracking();
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

    
    /**
     * Finds the closest remembered death tile. The memory is shared across the
     * Neoplasm controller family, but each controller keeps its own group state.
     */
    @Nullable
    public Tile closestDanger() {
        if (DodgeTile.isEmpty()) return null;
        return DodgeTile.copy().sort(tile -> tile.dst(unit)).first();
    }

    /**
     * Selects a nearby open land tile that is outside the remembered danger
     * radius and remains biased toward the unit's original destination.
     * Null means no safe alternative was found.
     */
    /** Minimum distance from a tile to any remembered death location. */
    public float dangerDistance(Tile tile) {
        if (tile == null || DodgeTile.isEmpty()) return Float.MAX_VALUE;

        float nearest = Float.MAX_VALUE;
        for (Tile danger : DodgeTile) {
            if (danger != null) nearest = Math.min(nearest, danger.dst(tile));
        }
        return nearest;
    }

    private boolean isNeoplasmUnit(Unit candidate) {
        if (candidate == null) return false;
        if (candidate.controller() instanceof NeoplasmAIController) return true;

        // Include Neoplasm types that deliberately retain their stock Mindustry AI.
        return candidate.type == RUnitTypes.pallium
            || candidate.type == RUnitTypes.miserable
            || candidate.type == RUnitTypes.hydroBomber
            || candidate.type == RUnitTypes.kalyx
            || candidate.type == RUnitTypes.mule
            || candidate.type == RUnitTypes.worm
            || candidate.type == RUnitTypes.blob
            || candidate.type == RUnitTypes.squid;
    }

    @Nullable
    protected Tile getClosestSafeTarget(int range, Tile danger, Tile destination) {
        if (danger == null || destination == null || unit == null) return null;

        int mid = range / 2;
        Seq<Tile> safeTiles = new Seq<>();

        for (int y = -mid; y < range; y++) {
            for (int x = -mid; x < range; x++) {
                Tile candidate = Vars.world.tile(unit.tileX() + x, unit.tileY() + y);
                if (candidate != null
                    && candidate.block() == Blocks.air
                    && candidate.floor() != null
                    && !candidate.floor().isLiquid
                    && dangerDistance(candidate) >= 80f) {
                    safeTiles.add(candidate);
                }
            }
        }

        if (safeTiles.isEmpty()) return null;
        return safeTiles.sort(candidate -> candidate.dst(destination)).first();
    }

    /**
     * Moves around a remembered death area without abandoning the real target.
     * Returns false when no safe step exists so normal movement can continue.
     */
    protected boolean avoidNearbyDanger(Tile destination) {
        if (destination == null) return false;

        Tile current = unit.tileOn();
        Tile danger = closestDanger();
        if (current == null || danger == null || dangerDistance(current) >= 80f) return false;

        Tile safeTarget = getClosestSafeTarget(15, danger, destination);
        if (safeTarget == null) safeTarget = getEdgeEscapeTile();

        if (safeTarget == null || safeTarget == current) return false;

        unit.movePref(vec.set(safeTarget.worldx(), safeTarget.worldy()).sub(unit).limit(unit.speed()));
        return true;
    }

    public void routeAir() {
        if (target == null) return;

        // Apply the PC death-memory reroute when a safe step is available.
        // If avoidance cannot find one, preserve the original direct target movement.
        Tile destination = target.tileOn();
        if (destination != null && avoidNearbyDanger(destination)) return;

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
        if (next == null || next == tile) {
            // Only force an escape move when currently inside the danger radius.
            // Far-away units keep following the normal flowfield until an
            // actual safe detour is needed.
            if (dangerDistance(tile) <= 40f) next = getEdgeEscapeTile();
            else return null;
        }

        return next == tile ? null : next;
    }

    @Nullable
    public Tile getEdgeEscapeTile() {
        if (unit == null || DodgeTile.isEmpty()) return null;

        Seq<Tile> candidates = new Seq<>();
        int radius = 15;
        int mid = radius / 2;

        // Search around the unit, not just the tiles immediately beside the
        // death cluster. The old implementation could select a tile that was
        // still inside the danger zone and steer units back toward it.
        for (int y = -mid; y < radius; y++) {
            for (int x = -mid; x < radius; x++) {
                Tile candidate = Vars.world.tile(unit.tileX() + x, unit.tileY() + y);
                if (candidate != null
                    && candidate != unit.tileOn()
                    && candidate.block() == Blocks.air
                    && candidate.floor() != null
                    && !candidate.floor().isLiquid
                    && dangerDistance(candidate) > 40f) {
                    candidates.add(candidate);
                }
            }
        }

        if (candidates.isEmpty()) return null;
        return candidates.sort(candidate ->
            candidate.dst(unit) - Math.min(dangerDistance(candidate) - 40f, 80f) * 0.5f
        ).first();
    }

    /**
     * Applies a death-aware flowfield step when one is available.
     * Returns false when the original controller should handle movement.
     */
    public boolean applyDodgePathfind(int pathTarget) {
        if (unit == null) return false;

        int costType = unit.type.flowfieldPathType;
        Tile tile = unit.tileOn();
        if (tile == null) return false;

        Tile dodgeTarget = getDodgeTarget(pathTarget, tile);
        if (dodgeTarget != null
            && dodgeTarget != tile
            && (costType != 2 || (dodgeTarget.floor() != null && dodgeTarget.floor().isLiquid))) {
            unit.movePref(vec.trns(
                unit.angleTo(dodgeTarget.worldx(), dodgeTarget.worldy()),
                unit.speed()
            ));
            return true;
        }

        return false;
    }

    @Override
    public void pathfind(int pathTarget) {
        int costType = this.unit.type.flowfieldPathType;
        Tile tile = this.unit.tileOn();
        if (tile == null) return;

        // Use avoidance only when it provides a distinct, valid next step.
        // Otherwise preserve the unit's original Mindustry flowfield movement.
        Tile dodgeTarget = getDodgeTarget(pathTarget, tile);
        if (dodgeTarget != null
            && dodgeTarget != tile
            && (costType != 2 || (dodgeTarget.floor() != null && dodgeTarget.floor().isLiquid))) {
            this.unit.movePref(vec.trns(
                this.unit.angleTo(dodgeTarget.worldx(), dodgeTarget.worldy()),
                this.unit.speed()
            ));
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
