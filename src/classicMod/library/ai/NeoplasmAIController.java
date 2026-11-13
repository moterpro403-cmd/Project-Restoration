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

    /** Every Neoplasm controller records its own route history automatically. */
    @Override
    public void updateUnit() {
        updateNeoplasmTracking();
        super.updateUnit();
    }

    // Short per-unit history prevents repeatedly reversing between the same tiles.
    private final int[] recentTiles = new int[16];
    private int recentTileCursor;
    private int recentTileCount;
    private int lastRecordedTile = -1;

    /** Initialize the shared death listener and record this unit's recent route. */
    public void updateNeoplasmTracking() {
        NeoplasmDeathHeatmap.init();
        if (unit == null) return;

        Tile current = unit.tileOn();
        if (current == null) return;
        int index = current.array();
        if (index == lastRecordedTile) return;

        recentTiles[recentTileCursor] = index;
        recentTileCursor = (recentTileCursor + 1) % recentTiles.length;
        recentTileCount = Math.min(recentTileCount + 1, recentTiles.length);
        lastRecordedTile = index;
    }

    /** Penalty for revisiting a recently crossed tile; newest visits cost most. */
    private float revisitPenalty(Tile tile) {
        if (tile == null) return 0f;
        int index = tile.array();
        for (int age = 0; age < recentTileCount; age++) {
            int position = (recentTileCursor - 1 - age + recentTiles.length) % recentTiles.length;
            if (recentTiles[position] == index) return 20f - age * 0.75f;
        }
        return 0f;
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
     * Choose a nearby flowfield step using destination cost + shared heat +
     * recent-visit penalty. Unlike strict downhill-only routing, this permits
     * a short uphill step to get around a hot patch. Recent tiles are penalized
     * so the unit does not bounce back and forth between the same two tiles.
     */
    @Nullable
    public Tile getDodgeTarget(int pathTarget, Tile tile) {
        if (tile == null || unit == null || RVars.pathfinderCustom == null) return null;

        var field = RVars.pathfinderCustom.getField(unit.team, unit.type.flowfieldPathType, pathTarget);
        if (field == null || !field.initialized || !field.hasTargets()) return null;

        int[] weights = field.hasComplete ? field.completeWeights : field.weights;
        int current = tile.array();
        if (current < 0 || current >= weights.length || weights[current] < 0) return null;

        int currentWeight = weights[current];
        float currentHeat = dangerDistance(tile);
        Tile best = null;
        float bestScore = Float.MAX_VALUE;
        Tile normalNext = null;
        int normalWeight = Integer.MAX_VALUE;
        float normalScore = Float.MAX_VALUE;

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
            if (progress < 0) continue;

            // Allow a bounded temporary increase in path cost for a real detour.
            if (progress > currentWeight + 72 && currentHeat < 25f) continue;

            float heat = dangerDistance(candidate);
            float score = progress
                + heat * NeoplasmDeathHeatmap.pathHeatWeight
                + revisitPenalty(candidate);

            if (progress < normalWeight) {
                normalWeight = progress;
                normalNext = candidate;
                normalScore = progress
                    + heat * NeoplasmDeathHeatmap.pathHeatWeight
                    + revisitPenalty(candidate);
            }

            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null || normalNext == null) return null;

        // Do nothing when neither the current tile nor the normal next tile is hot.
        float normalHeat = dangerDistance(normalNext);
        if (currentHeat < 2f && normalHeat < 2f) return null;

        // Compare the safer option against both staying on course and escaping
        // the current hot tile. Strong heat can justify a bounded uphill detour.
        float currentScore = currentWeight
            + currentHeat * NeoplasmDeathHeatmap.pathHeatWeight;
        float baseline = Math.min(normalScore, currentScore);
        if (bestScore + 0.5f < baseline) return best;

        // When already deep inside a hot zone, take the least-cost valid escape
        // step even if it temporarily moves away from the destination.
        if (currentHeat >= 25f && dangerDistance(best) < currentHeat
            && revisitPenalty(best) < 20f) return best;

        return null;
    }

    /** Soft local detour for direct-movement flying and Hug-style units. */
    protected boolean avoidNearbyDanger(Tile destination) {
        if (destination == null || unit == null) return false;
        Tile current = unit.tileOn();
        if (current == null) return false;

        Tile best = null;
        float bestScore = Float.MAX_VALUE;
        Tile normalNext = null;
        float normalScore = Float.MAX_VALUE;

        for (Point2 offset : Geometry.d8) {
            Tile candidate = Vars.world.tile(current.x + offset.x, current.y + offset.y);
            if (candidate == null || !unit.canPass(candidate.x, candidate.y)) continue;
            if (offset.x != 0 && offset.y != 0
                && (!unit.canPass(current.x + offset.x, current.y)
                || !unit.canPass(current.x, current.y + offset.y))) continue;

            float heat = dangerDistance(candidate);
            float score = candidate.dst(destination)
                + heat * NeoplasmDeathHeatmap.directHeatWeight
                + revisitPenalty(candidate);

            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
            if (candidate.dst(destination) < (normalNext == null ? Float.MAX_VALUE : normalNext.dst(destination))) {
                normalNext = candidate;
                normalScore = score;
            }
        }

        if (best == null || normalNext == null) return false;
        float currentHeat = dangerDistance(current);
        float normalHeat = dangerDistance(normalNext);
        if (currentHeat < 2f && normalHeat < 2f) return false;

        float baseline = Math.min(normalScore,
            current.dst(destination) + currentHeat * NeoplasmDeathHeatmap.directHeatWeight);
        if (bestScore + 0.5f >= baseline && currentHeat < 25f) return false;

        if (best == normalNext && currentHeat < 25f) return false;
        if (currentHeat >= 25f && dangerDistance(best) >= currentHeat
            && revisitPenalty(best) >= 20f) return false;

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
