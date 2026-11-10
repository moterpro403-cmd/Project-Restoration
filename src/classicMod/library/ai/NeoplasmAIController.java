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

    // Ground units use the same progress timeout as the vanilla GroundAI.
    private float stuckTime = 0f;
    private float stuckX = -9999f, stuckY = -9999f;

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

        // Keep danger memory local to the unit. Old death sites far across the
        // map should not keep influencing its route forever.
        DodgeTile.removeAll(tile -> tile == null || tile.dst(unit) > 240f);

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

    /** Moves away from a remembered death zone without abrupt tile-to-tile turns. */
    protected boolean avoidNearbyDanger(Tile destination) {
        Tile current = unit.tileOn();
        Tile danger = closestDanger();
        if (current == null || danger == null) return false;

        float distance = danger.dst(unit);
        if (distance >= 80f) return false;

        float pressure = Mathf.clamp((80f - distance) / 80f);

        // Flying units can move freely: blend their attack heading with a
        // smooth repulsion vector instead of forcing them onto ground tiles.
        if (unit.isFlying()) {
            vec.set(
                    destination == null ? unit.vel.x : destination.worldx() - unit.x,
                    destination == null ? unit.vel.y : destination.worldy() - unit.y
            );
            if (vec.isZero()) vec.set(1f, 0f);
            vec.nor();

            vec.add(arc.util.Tmp.v1.set(unit.x - danger.worldx(), unit.y - danger.worldy())
                    .nor().scl(pressure * 1.8f));

            if (vec.isZero()) return false;
            unit.movePref(vec.setLength(unit.speed()));
            return true;
        }

        // Ground units need a genuinely passable escape tile.
        Tile safe = getClosestTarget(15, danger, destination, unit);
        if (safe == null) safe = getEdgeEscapeTile();

        if (safe == null || safe == current) {
            // A completely surrounded set of remembered tiles should not
            // permanently immobilize a unit.
            DodgeTile.remove(danger);
            return false;
        }

        vec.set(safe.worldx() - unit.x, safe.worldy() - unit.y);
        if (!vec.isZero()) {
            unit.movePref(vec.setLength(unit.speed()));
            return true;
        }

        return false;
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
        if (target == null) return;

        vec.set(target.getX() - unit.x, target.getY() - unit.y);
        if (vec.isZero()) return;

        Tile danger = closestDanger();
        if (danger != null) {
            float distance = danger.dst(unit);
            if (distance < 80f) {
                float pressure = Mathf.clamp((80f - distance) / 80f);
                vec.nor().add(arc.util.Tmp.v1
                        .set(unit.x - danger.worldx(), unit.y - danger.worldy())
                        .nor().scl(pressure * 1.8f));
            }
        }

        if (!vec.isZero()) unit.movePref(vec.setLength(unit.speed()));
    }

    private boolean movementStuck() {
        if (unit == null || unit.isFlying() || unit.type.speed <= 0.001f) return false;

        float stuckRange = Vars.tilesize * 1.5f;
        float stuckThreshold = Math.max(1f, stuckRange * 2f / unit.type.speed);

        if (!unit.within(stuckX, stuckY, stuckRange)) {
            stuckX = unit.x;
            stuckY = unit.y;
            stuckTime = 0f;
        } else {
            stuckTime += Time.delta;
        }

        return stuckTime >= stuckThreshold;
    }

    @Override
    public void pathfind(int pathTarget) {
        int costType = this.unit.type.flowfieldPathType;
        Tile tile = this.unit.tileOn();
        if (tile == null) return;

        // When progress stalls, use the engine's native crowd-avoidance path
        // instead of repeatedly selecting the same local neighbor.
        if (movementStuck()) {
            super.pathfind(pathTarget, true, true);
            return;
        }

        if (RVars.pathfinderCustom == null) {
            super.pathfind(pathTarget);
            return;
        }

        var field = RVars.pathfinderCustom.getField(this.unit.team, costType, pathTarget);
        if (field == null || !field.initialized) {
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
            vec.trns(unit.angleTo(targetTile.worldx(), targetTile.worldy()), prefSpeed());
            unit.movePref(vec);
        } else {
            // Do not greedily bounce between blocked adjacent tiles.
            super.pathfind(pathTarget, true, true);
        }
    }
}
