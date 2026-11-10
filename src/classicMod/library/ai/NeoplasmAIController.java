package classicMod.library.ai;

import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.*;
import classicMod.content.RVars;
import classicMod.library.GeometryPlus;
import classicMod.library.blocks.neoplasiaBlocks.CausticHeart;
import mindustry.Vars;
import mindustry.ai.Pathfinder;
import mindustry.content.Blocks;
import mindustry.entities.Units;
import mindustry.entities.units.AIController;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.Turret;

public class NeoplasmAIController extends AIController {

    public Seq<Tile> DodgeTile = new Seq<>();
    public Seq<Unit> groups = new Seq<>();

    public boolean ignore;

    private Tile currentSafeTarget = null;
    private float rerouteCooldown = 0f; // Time left before recomputing a new safe tile

    // Use ObjectSet for fast lookup
    public ObjectSet<Unit> knownNeoplasms = new ObjectSet<>();
    public Seq<Unit> neoplasmGroup = new Seq<>();
    private final Seq<Unit> toRemove = new Seq<>();

    @Override
    public void updateTargeting() {
        if (unit.hasWeapons()) {
            super.updateTargeting();
            return;
        }

        // Weaponless crawlers still need targets; the base AI only retargets
        // from updateWeapons(), which is skipped when a unit has no weapons.
        if (retarget()) {
            target = findMainTarget(
                    unit.x, unit.y,
                    Math.max(unit.range(), 240f),
                    unit.type.targetAir, unit.type.targetGround
            );
        }

        if (target != null && invalid(target)) {
            target = null;
            targetInvalidated();
        }
    }

    @Override
    public void updateUnit() {
        // Look for nearby untracked neoplasms
        Unit neo = Units.closest(unit.team, unit.x, unit.y, u ->
                u.controller() instanceof NeoplasmAIController &&
                        !knownNeoplasms.contains(u) &&
                        u != this.unit
        );

        if (neo != null) {
            knownNeoplasms.add(neo);
            neoplasmGroup.add(neo);
        }

        // Reuse the scratch sequence instead of allocating one for every AI tick.
        toRemove.clear();

        for (var neoplasm : neoplasmGroup) {
            if (neoplasm == null || !neoplasm.dead) continue;

            Tile deadTile = neoplasm.tileOn();
            if (deadTile != null && neoplasm.controller() instanceof NeoplasmAIController ai && !ai.ignore) {
                // Add central and surrounding tiles if not already in DodgeTile
                for (var point : GeometryPlus.d8plus) { // includes center + 8 surrounding
                    Tile t = Vars.world.tile(deadTile.x + point.x, deadTile.y + point.y);
                    if (t != null) DodgeTile.addUnique(t);
                }
            }

            // Remove from group tracking
            toRemove.add(neoplasm);
            knownNeoplasms.remove(neoplasm);
        }

        neoplasmGroup.removeAll(toRemove);
        toRemove.clear();


        if (rerouteCooldown > 0) rerouteCooldown -= Time.delta;
        super.updateUnit();
    }

    @Nullable
    public Tile getClosestVent(boolean dontPlaceNearDangerous) {
        Seq<Tile> avaliableVents = PathfinderExtended.SteamVents.copy().removeAll(tile -> tile.build instanceof CausticHeart.HeartBuilding || tile.block() != Blocks.air);
        Tile vent = Geometry.findClosest(this.unit.x, this.unit.y, avaliableVents);

        if (vent == null) return null;
        Building nearbyEnemyTile = Units.findEnemyTile(this.unit.team, vent.getX(), vent.getY(), 240f, building -> !building.dead);
        if (dontPlaceNearDangerous && nearbyEnemyTile != null) avaliableVents = avaliableVents.copy().removeAll(tile -> tile.dst(nearbyEnemyTile) <= 80f);
        vent = Geometry.findClosest(this.unit.x, this.unit.y, avaliableVents);
        return (vent != null && !(vent.build instanceof CausticHeart.HeartBuilding)) ? vent : null;
    }

    @Nullable
    public Tile getClosestVent() {
        return getClosestVent(false);
    }

    public Tile closestDanger(){
        if (DodgeTile.isEmpty()) return null;

        Tile closest = null;
        float closestDistance = Float.MAX_VALUE;
        for (Tile tile : DodgeTile) {
            if (tile == null) continue;
            float distance = tile.dst(this.unit);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = tile;
            }
        }
        return closest;
    }

    public Tile getClosestTarget(int range, Tile closestDanger, Tile targetTile, Unit unit){
        int radius = Math.max(2, Mathf.floor(range / 2f));
        Seq<Tile> availableLand = new Seq<>();

        // Search symmetrically around the unit; the old loop searched much
        // farther to the right/down than to the left/up.
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                Tile candidate = Vars.world.tile(unit.tileX() + x, unit.tileY() + y);
                if (candidate == null
                        || candidate.block() != Blocks.air
                        || candidate.floor() == null
                        || candidate.floor().isLiquid) continue;

                float distanceFromUnit = unit.dst(candidate);
                if (distanceFromUnit < 10f || distanceFromUnit > 140f) continue;
                if (closestDanger != null && closestDanger.dst(candidate) < 80f) continue;
                availableLand.add(candidate);
            }
        }

        if (availableLand.isEmpty()) return null;
        Tile destination = targetTile != null ? targetTile : unit.tileOn();
        if (destination == null) return availableLand.first();

        availableLand.sort(candidate -> {
            float travelCost = candidate.dst(unit) * 0.15f;
            float dangerBonus = closestDanger == null ? 0f : Math.min(closestDanger.dst(candidate), 160f) * 0.05f;
            return candidate.dst(destination) + travelCost - dangerBonus;
        });
        return availableLand.first();
    }

    /** Moves away from a remembered death zone before resuming the attack route. */
    protected boolean avoidNearbyDanger(Tile destination) {
        Tile current = unit.tileOn();
        Tile danger = closestDanger();
        if (current == null || danger == null || danger.dst(current) >= 80f) return false;

        Tile safe = getClosestTarget(15, danger, destination, unit);
        if (safe == null) safe = getEdgeEscapeTile();

        if (safe == null || safe == current) {
            DodgeTile.remove(danger);
            return false;
        }

        unit.movePref(vec.set(safe.worldx(), safe.worldy()).sub(unit).limit(unit.speed()));
        return true;
    }

    /** Picks a passable neighboring tile that reduces distance to a blocked target. */
    protected boolean moveAroundBlockedTarget(Tile destination) {
        Tile current = unit.tileOn();
        if (current == null || destination == null) return false;

        Tile best = null;
        float bestScore = Float.MAX_VALUE;
        for (Point2 offset : Geometry.d8) {
            Tile next = Vars.world.tile(current.x + offset.x, current.y + offset.y);
            if (next == null || !unit.canPass(next.x, next.y) || DodgeTile.contains(next)) continue;

            float score = next.dst(destination) + current.dst(next) * 0.2f;
            if (next.dangerous()) score += 32f;
            if (score < bestScore) {
                bestScore = score;
                best = next;
            }
        }

        if (best == null) return false;
        unit.movePref(vec.set(best.worldx(), best.worldy()).sub(unit).limit(unit.speed()));
        return true;
    }

    @Nullable
    public Tile getEdgeEscapeTile(){
        Seq<Tile> candidates = new Seq<>();

        for(Tile danger : DodgeTile){
            for(Point2 offset : Geometry.d8){
                Tile neighbor = Vars.world.tile(danger.x + offset.x, danger.y + offset.y);
                if(
                        neighbor != null &&
                                !DodgeTile.contains(neighbor) &&
                                neighbor.block() == Blocks.air &&
                                neighbor.floor() != null &&
                                !neighbor.floor().isLiquid
                ){
                    candidates.add(neighbor);
                }
            }
        }

        if(candidates.isEmpty()) return null;
        return candidates.sort(tile -> tile.dst(unit)).first(); // Closest edge tile
    }

    public void routeAir(){
        Tile tile = this.unit.tileOn();
        Tile targetTile = target != null ? target.tileOn() : null;
        Tile nearDanger = closestDanger();

        // Reroute if too close to danger
        if (nearDanger != null && targetTile != null) {
            float distance = nearDanger.dst(tile);
            if (distance < 80f){
                Tile safeTarget = getClosestTarget(15, nearDanger, targetTile, unit);
                if (safeTarget != null){
                    targetTile = safeTarget;
                } else {
                    // Use edge escape if can't find a good safe target
                    targetTile = getEdgeEscapeTile();
                }
            }
        }

        // Move toward safe target
        if (targetTile != null && tile != targetTile){
            unit.movePref(vec.set(targetTile).sub(unit).limit(unit.speed()));
        }
    }

    @Override
    public void pathfind(int pathTarget) {
        int costType = this.unit.type.flowfieldPathType;
        Tile tile = this.unit.tileOn();
        if (tile == null) return;

        if (RVars.pathfinderCustom == null) {
            super.pathfind(pathTarget);
            return;
        }

        var field = RVars.pathfinderCustom.getField(this.unit.team, costType, pathTarget);
        if (field == null) {
            super.pathfind(pathTarget);
            return;
        }

        // Ask the custom field for a route that avoids remembered death zones.
        Tile targetTile = RVars.pathfinderCustom.getTargetTileDodge(tile, field, DodgeTile);
        if (targetTile == null || targetTile == tile) {
            super.pathfind(pathTarget);
            return;
        }

        if (unit.canPass(targetTile.x, targetTile.y)) {
            unit.movePref(vec.trns(
                    unit.angleTo(targetTile.worldx(), targetTile.worldy()),
                    unit.speed()
            ));
        } else {
            // If the flowfield step is blocked, attempt a local passable sidestep.
            moveAroundBlockedTarget(targetTile);
        }
    }
}
