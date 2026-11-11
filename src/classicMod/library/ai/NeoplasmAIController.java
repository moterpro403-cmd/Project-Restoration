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

    private float discoveryTimer;
    private final Seq<Unit> deadUnitBuffer = new Seq<>();

    @Override
    public void updateUnit() {
        // Discover allies periodically rather than running a nearest-unit search
        // for every Neoplasm on every simulation tick.
        if (discoveryTimer <= 0f) {
            discoveryTimer = 10f;
            Unit neo = Units.closest(unit.team, unit.x, unit.y, u ->
                    u.controller() instanceof NeoplasmAIController &&
                            !knownNeoplasms.contains(u) &&
                            u != this.unit
            );

            if (neo != null) {
                knownNeoplasms.add(neo);
                neoplasmGroup.add(neo);
            }
        } else {
            discoveryTimer -= Time.delta;
        }

        // Reuse this buffer; allocating a new Seq every tick caused avoidable GC.
        deadUnitBuffer.clear();
        for (Unit neoplasm : neoplasmGroup) {
            if (neoplasm == null) continue;
            if (!neoplasm.dead) continue;

            Tile deadTile = neoplasm.tileOn();
            if (deadTile != null && neoplasm.controller() instanceof NeoplasmAIController ai && !ai.ignore) {
                for (var point : GeometryPlus.d8plus) {
                    Tile t = Vars.world.tile(deadTile.x + point.x, deadTile.y + point.y);
                    if (t != null) DodgeTile.addUnique(t);
                }
            }

            deadUnitBuffer.add(neoplasm);
            knownNeoplasms.remove(neoplasm);
        }

        neoplasmGroup.removeAll(deadUnitBuffer);

        if (rerouteCooldown > 0f) rerouteCooldown -= Time.delta;
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

    public Tile closestDanger() {
        Tile closest = null;
        float bestDistance = Float.MAX_VALUE;

        for (Tile candidate : DodgeTile) {
            if (candidate == null) continue;
            float distance = candidate.dst(this.unit);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = candidate;
            }
        }
        return closest;
    }

    public Tile getClosestTarget(int range, Tile closestDanger, Tile targetTile, Unit unit) {
        if (closestDanger == null || targetTile == null || unit == null) return null;

        int mid = Mathf.floor((float)range / 2f);
        Tile best = null;
        float bestDistance = Float.MAX_VALUE;

        // Select the best valid escape tile directly; no temporary Seq or sort.
        for (int y = -mid; y < range; y++) {
            for (int x = -mid; x < range; x++) {
                Tile tile = Vars.world.tile(unit.tileX() + x, unit.tileY() + y);
                if (tile == null || tile.block() != Blocks.air ||
                        tile.floor() == null || tile.floor().isLiquid) continue;

                if (closestDanger.dst(tile) < 80f) continue;
                float unitDistance = unit.dst(tile);
                if (unitDistance < 10f || unitDistance > 100f) continue;

                float targetDistance = tile.dst(targetTile);
                if (targetDistance < bestDistance) {
                    bestDistance = targetDistance;
                    best = tile;
                }
            }
        }
        return best;
    }

    @Nullable
    public Tile getEdgeEscapeTile() {
        Tile best = null;
        float bestDistance = Float.MAX_VALUE;

        for (Tile danger : DodgeTile) {
            if (danger == null) continue;
            for (Point2 offset : Geometry.d8) {
                Tile neighbor = Vars.world.tile(danger.x + offset.x, danger.y + offset.y);
                if (neighbor == null || DodgeTile.contains(neighbor) ||
                        neighbor.block() != Blocks.air || neighbor.floor() == null ||
                        neighbor.floor().isLiquid) continue;

                float distance = neighbor.dst(unit);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = neighbor;
                }
            }
        }
        return best;
    }

    public void routeAir() {
        if (unit == null) return;
        Tile tile = unit.tileOn();
        if (tile == null) return;

        Tile targetTile = target != null ? target.tileOn() : null;
        Tile nearDanger = closestDanger();

        if (nearDanger != null && targetTile != null && nearDanger.dst(tile) < 80f) {
            Tile safeTarget = getClosestTarget(15, nearDanger, targetTile, unit);
            targetTile = safeTarget != null ? safeTarget : getEdgeEscapeTile();
        }

        if (targetTile != null && tile != targetTile) {
            unit.movePref(vec.set(targetTile).sub(unit).limit(unit.speed()));
        }
    }

    @Override
    public void pathfind(int pathTarget) {
        int costType = this.unit.type.flowfieldPathType;
        Tile tile = this.unit.tileOn();
        if (tile == null) return;

        // Attempt to get a pathfinding target tile avoiding DodgeTile
        if (RVars.pathfinderCustom == null) return;
        Tile targetTile = RVars.pathfinderCustom.getTargetTileDodge(
                tile,
                RVars.pathfinderCustom.getField(this.unit.team, costType, pathTarget),
                DodgeTile
        );

        Tile nearDanger = closestDanger();

        // Try rerouting if near danger
        if (nearDanger != null) {
            float distance = nearDanger.dst(tile);
            if (distance < 80f) {
                Tile safeTarget = getClosestTarget(15, nearDanger, targetTile, unit);
                if (safeTarget != null) {
                    targetTile = safeTarget;
                } else {
                    // Fallback: move to tile on edge of danger zone
                    targetTile = getEdgeEscapeTile();
                }

                // Optional: remove the danger if no alternative found
                if (targetTile == null) {
                    DodgeTile.remove(nearDanger);
                }
            }
        }

        if (targetTile != null && tile != targetTile) {
            // Only move if it's a valid move (don't path to self)
            if (costType != 2 || targetTile.floor().isLiquid) {
                this.unit.movePref(vec.trns(
                        this.unit.angleTo(targetTile.worldx(), targetTile.worldy()),
                        this.unit.speed()
                ));
            }
        }
    }
}
