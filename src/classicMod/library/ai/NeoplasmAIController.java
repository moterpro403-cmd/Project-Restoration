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

    public Seq<Unit> groups = new Seq<>();

    public boolean update;
    public boolean ignore;

    /** Deaths are recorded globally by NeoplasmDeathHeatmap's UnitDestroyEvent hook. */
    public void updateNeoplasmTracking() {
        NeoplasmDeathHeatmap.init();
    }


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
    /** Heat around a tile is shared by every Neoplasm unit on the same team. */
    public float dangerDistance(Tile tile) {
        return tile == null ? 0f : NeoplasmDeathHeatmap.getHeat(unit.team, tile);
    }

    /**
     * Choose a nearby passable tile that makes progress while minimizing shared
     * death heat. This is a soft cost, not a hard path block.
     */
    @Nullable
    public Tile getDodgeTarget(int pathTarget, Tile tile) {
        if (tile == null || unit == null || RVars.pathfinderCustom == null) return null;

        var field = RVars.pathfinderCustom.getField(
            unit.team, unit.type.flowfieldPathType, pathTarget
        );
        if (field == null || !field.initialized || !field.hasTargets()) return null;

        int[] weights = field.hasComplete ? field.completeWeights : field.weights;
        int current = tile.array();
        if (current < 0 || current >= weights.length) return null;

        Tile best = null;
        float bestScore = Float.MAX_VALUE;
        for (Point2 offset : Geometry.d8) {
            Tile candidate = Vars.world.tile(tile.x + offset.x, tile.y + offset.y);
            if (candidate == null) continue;

            int index = candidate.array();
            if (index < 0 || index >= weights.length || !field.passable(index)) continue;
            if (offset.x != 0 && offset.y != 0) {
                Tile horizontal = Vars.world.tile(tile.x + offset.x, tile.y);
                Tile vertical = Vars.world.tile(tile.x, tile.y + offset.y);
                if (horizontal == null || vertical == null
                    || !field.passable(horizontal.array()) || !field.passable(vertical.array())) continue;
            }

            int progress = weights[index];
            if (progress < 0 || progress >= weights[current]) continue;

            float heat = NeoplasmDeathHeatmap.getHeat(unit.team, candidate);
            float score = progress + heat * NeoplasmDeathHeatmap.pathHeatWeight;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        float currentHeat = NeoplasmDeathHeatmap.getHeat(unit.team, tile);
        if (currentHeat < 2f) return null;
        return best;
    }

    /** Soft local detour for direct-movement flying and Hug-style units. */
    protected boolean avoidNearbyDanger(Tile destination) {
        if (destination == null || unit == null) return false;
        Tile current = unit.tileOn();
        if (current == null || NeoplasmDeathHeatmap.getHeat(unit.team, current) < 2f) return false;

        Tile best = null;
        float bestScore = Float.MAX_VALUE;
        for (Point2 offset : Geometry.d8) {
            Tile candidate = Vars.world.tile(current.x + offset.x, current.y + offset.y);
            if (candidate == null || !unit.canPass(candidate.x, candidate.y)) continue;
            if (offset.x != 0 && offset.y != 0) {
                if (!unit.canPass(current.x + offset.x, current.y)
                    || !unit.canPass(current.x, current.y + offset.y)) continue;
            }

            float progress = candidate.dst(destination);
            float heat = NeoplasmDeathHeatmap.getHeat(unit.team, candidate);
            float score = progress + heat * NeoplasmDeathHeatmap.directHeatWeight;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null || best == current) return false;
        float currentScore = current.dst(destination)
            + NeoplasmDeathHeatmap.getHeat(unit.team, current) * NeoplasmDeathHeatmap.directHeatWeight;
        if (bestScore >= currentScore) return false;

        unit.movePref(vec.set(best.worldx(), best.worldy()).sub(unit).limit(unit.speed()));
        return true;
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
        if (tile == null || RVars.pathfinderCustom == null) return null;

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
        if (unit == null) return null;

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
            if (NeoplasmDeathHeatmap.getHeat(unit.team, targetTile) > 2f) targetTile = getClosestTarget(240);
            if (targetTile != null) {
                this.unit.movePref(vec.trns(
                    this.unit.angleTo(targetTile.worldx(), targetTile.worldy()),
                    this.unit.speed()
                ));
            }
        }
    }
}
