package classicMod.library.ai;

import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.struct.Seq;
import arc.util.Nullable;
import classicMod.content.RVars;
import classicMod.library.blocks.neoplasiaBlocks.CausticHeart;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.AIController;
import mindustry.world.Tile;

public class NeoplasmAIController extends AIController {
    public boolean update;
    public boolean ignore;

    /** Initialize the shared death listener once. */
    public void updateNeoplasmTracking() {
        NeoplasmDeathHeatmap.init();
    }

    @Nullable
    public Tile getClosestVent() {
        Seq<Tile> available = PathfinderExtended.SteamVents.copy()
            .removeAll(tile -> tile.build instanceof CausticHeart.HeartBuilding);
        Tile vent = Geometry.findClosest(unit.x, unit.y, available);
        return vent != null && !(vent.build instanceof CausticHeart.HeartBuilding) ? vent : null;
    }

    @Nullable
    public Tile getClosestVent(boolean avoidDangerous) {
        return getClosestVent();
    }

    public Tile getClosestTarget(int range) {
        int r = (range % 2 == 1) ? range + 1 : range;
        int mid = r / 2;
        Seq<Tile> available = new Seq<>();
        for (int y = -mid; y < r; y++) {
            for (int x = -mid; x < r; x++) {
                Tile tile = Vars.world.tile(unit.tileX() + x, unit.tileY() + y);
                if (tile != null && tile.block() == Blocks.air
                    && tile.floor() != null && !tile.floor().isLiquid) {
                    available.add(tile);
                }
            }
        }
        if (available.isEmpty()) return null;
        available.sort(tile -> tile.dst(target));
        return available.first();
    }

    /** Returns the shared heat at a tile (higher means more dangerous). */
    public float dangerDistance(Tile tile) {
        return tile == null ? 0f : NeoplasmDeathHeatmap.getHeat(unit.team, tile);
    }

    /**
     * Select a passable neighbor that advances the flowfield while minimizing
     * the shared heat penalty. The field is team-wide, not per controller.
     */
    @Nullable
    public Tile getDodgeTarget(int pathTarget, Tile tile) {
        if (tile == null || unit == null || RVars.pathfinderCustom == null) return null;

        var field = RVars.pathfinderCustom.getField(unit.team, unit.type.flowfieldPathType, pathTarget);
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
            if (index < 0 || index >= weights.length || !unit.canPass(candidate.x, candidate.y)) continue;

            if (offset.x != 0 && offset.y != 0) {
                Tile horizontal = Vars.world.tile(tile.x + offset.x, tile.y);
                Tile vertical = Vars.world.tile(tile.x, tile.y + offset.y);
                if (horizontal == null || vertical == null
                    || !unit.canPass(horizontal.x, horizontal.y) || !unit.canPass(vertical.x, vertical.y)) continue;
            }

            int progress = weights[index];
            if (progress < 0 || progress >= weights[current]) continue;

            float score = progress + dangerDistance(candidate) * NeoplasmDeathHeatmap.pathHeatWeight;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return dangerDistance(tile) >= 2f ? best : null;
    }

    /** Soft detour for direct-movement flying and Hug-style units. */
    protected boolean avoidNearbyDanger(Tile destination) {
        if (destination == null || unit == null) return false;
        Tile current = unit.tileOn();
        if (current == null || dangerDistance(current) < 2f) return false;

        Tile best = null;
        float bestScore = Float.MAX_VALUE;
        for (Point2 offset : Geometry.d8) {
            Tile candidate = Vars.world.tile(current.x + offset.x, current.y + offset.y);
            if (candidate == null || !unit.canPass(candidate.x, candidate.y)) continue;
            if (offset.x != 0 && offset.y != 0
                && (!unit.canPass(current.x + offset.x, current.y)
                || !unit.canPass(current.x, current.y + offset.y))) continue;

            float score = candidate.dst(destination)
                + dangerDistance(candidate) * NeoplasmDeathHeatmap.directHeatWeight;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null) return false;
        float currentScore = current.dst(destination)
            + dangerDistance(current) * NeoplasmDeathHeatmap.directHeatWeight;
        if (bestScore >= currentScore) return false;

        unit.movePref(vec.set(best.worldx(), best.worldy()).sub(unit).limit(unit.speed()));
        return true;
    }

    public void routeAir() {
        if (target == null) return;
        Tile destination = target.tileOn();
        if (destination != null && avoidNearbyDanger(destination)) return;
        vec.set(target.getX() - unit.x, target.getY() - unit.y);
        if (!vec.isZero()) unit.movePref(vec.setLength(unit.speed()));
    }

    public boolean applyDodgePathfind(int pathTarget) {
        if (unit == null) return false;
        int costType = unit.type.flowfieldPathType;
        Tile tile = unit.tileOn();
        if (tile == null) return false;

        Tile next = getDodgeTarget(pathTarget, tile);
        if (next == null || next == tile
            || (costType == 2 && (next.floor() == null || !next.floor().isLiquid))) return false;

        unit.movePref(vec.trns(unit.angleTo(next.worldx(), next.worldy()), unit.speed()));
        return true;
    }

    @Override
    public void pathfind(int pathTarget) {
        int costType = unit.type.flowfieldPathType;
        Tile tile = unit.tileOn();
        if (tile == null) return;

        Tile next = getDodgeTarget(pathTarget, tile);
        if (next != null && next != tile
            && (costType != 2 || (next.floor() != null && next.floor().isLiquid))) {
            unit.movePref(vec.trns(unit.angleTo(next.worldx(), next.worldy()), unit.speed()));
            return;
        }

        Tile targetTile = Vars.pathfinder.getTargetTile(tile,
            Vars.pathfinder.getField(unit.team, costType, pathTarget));
        if (targetTile != null && targetTile != tile
            && (costType != 2 || (targetTile.floor() != null && targetTile.floor().isLiquid))) {
            unit.movePref(vec.trns(unit.angleTo(targetTile.worldx(), targetTile.worldy()), unit.speed()));
        }
    }
}
