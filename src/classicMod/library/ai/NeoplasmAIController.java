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
    private final ObjectSet<Tile> dodgeLookup = new ObjectSet<>();
    private final Seq<Tile> nearbyDodgeBuffer = new Seq<>(8);
    public Seq<Unit> groups = new Seq<>();

    public boolean ignore;

    private Tile currentSafeTarget = null;
    private Tile currentSafeGoal = null;
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

            // Dead-ally cleanup uses the same cadence as discovery. Checking the
            // entire remembered group every frame multiplied this work by unit count.
            deadUnitBuffer.clear();
            for (Unit neoplasm : neoplasmGroup) {
                if (neoplasm == null || !neoplasm.dead) continue;

                Tile deadTile = neoplasm.tileOn();
                if (deadTile != null && neoplasm.controller() instanceof NeoplasmAIController ai && !ai.ignore) {
                    for (var point : GeometryPlus.d8plus) {
                        Tile t = Vars.world.tile(deadTile.x + point.x, deadTile.y + point.y);
                        if (t != null && dodgeLookup.add(t)) DodgeTile.add(t);
                    }
                }

                deadUnitBuffer.add(neoplasm);
                knownNeoplasms.remove(neoplasm);
            }

            neoplasmGroup.removeAll(deadUnitBuffer);
        } else {
            discoveryTimer -= Time.delta;
        }

        if (rerouteCooldown > 0f) rerouteCooldown -= Time.delta;
        super.updateUnit();
    }

    /** Finds the nearest currently usable vent without copying the global vent list. */
    private Tile closestAvailableVent(Building avoid) {
        Tile best = null;
        float bestDistance = Float.MAX_VALUE;

        for (Tile candidate : PathfinderExtended.SteamVents) {
            if (candidate == null || candidate.block() != Blocks.air ||
                    candidate.build instanceof CausticHeart.HeartBuilding) continue;
            if (avoid != null && candidate.dst(avoid) <= 80f) continue;

            float distance = candidate.dst(unit);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    @Nullable
    public Tile getClosestVent(boolean dontPlaceNearDangerous) {
        if (unit == null) return null;

        Tile nearest = closestAvailableVent(null);
        if (nearest == null || !dontPlaceNearDangerous) return nearest;

        Building nearbyEnemyTile = Units.findEnemyTile(unit.team, nearest.getX(), nearest.getY(), 240f, building -> !building.dead);
        return nearbyEnemyTile == null ? nearest : closestAvailableVent(nearbyEnemyTile);
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

        // Cache only hazards close enough to intersect this local search area.
        // Each candidate can then account for overlapping danger zones without
        // rescanning distant historical markers.
        nearbyDodgeBuffer.clear();
        for (Tile danger : DodgeTile) {
            if (danger != null && danger.dst(unit) <= 144f) nearbyDodgeBuffer.add(danger);
        }

        // Search a symmetric area around the unit; the former -mid..range
        // bounds heavily favored the positive X/Y directions.
        for (int y = -mid; y <= mid; y++) {
            for (int x = -mid; x <= mid; x++) {
                Tile tile = Vars.world.tile(unit.tileX() + x, unit.tileY() + y);
                if (tile == null || tile.block() != Blocks.air ||
                        tile.floor() == null || tile.floor().isLiquid ||
                        !unit.canPass(tile.x, tile.y)) continue;

                float clearance = closestDanger.dst(tile);
                for (Tile danger : nearbyDodgeBuffer) {
                    float dangerDistance = danger.dst(tile);
                    if (dangerDistance < clearance) clearance = dangerDistance;
                }
                if (clearance < 80f) continue;
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
                if (neighbor == null || dodgeLookup.contains(neighbor) ||
                        neighbor.block() != Blocks.air || neighbor.floor() == null ||
                        neighbor.floor().isLiquid || !unit.canPass(neighbor.x, neighbor.y)) continue;

                float distance = neighbor.dst(unit);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = neighbor;
                }
            }
        }
        return best;
    }

    private boolean validCachedSafeTarget(Tile candidate, Unit mover) {
        if (candidate == null || candidate.block() != Blocks.air || candidate.floor() == null ||
                candidate.floor().isLiquid || !mover.canPass(candidate.x, candidate.y) ||
                mover.dst(candidate) <= 8f) return false;

        // Do not keep steering toward an escape tile that has become unsafe
        // because another Neoplasm died nearby.
        for (Tile danger : DodgeTile) {
            if (danger != null && danger.dst(candidate) < 80f) return false;
        }
        return true;
    }

    public void routeAir() {
        if (unit == null) return;
        Tile tile = unit.tileOn();
        if (tile == null) return;

        Tile targetTile = target != null ? target.tileOn() : null;
        Tile nearDanger = closestDanger();

        if (nearDanger != null && targetTile != null && nearDanger.dst(tile) < 80f) {
            if (rerouteCooldown <= 0f || currentSafeGoal != targetTile ||
                    !validCachedSafeTarget(currentSafeTarget, unit)) {
                currentSafeTarget = getClosestTarget(15, nearDanger, targetTile, unit);
                if (currentSafeTarget == null) currentSafeTarget = getEdgeEscapeTile();
                currentSafeGoal = targetTile;
                rerouteCooldown = 10f;
            }

            targetTile = currentSafeTarget;
        } else {
            currentSafeTarget = null;
            currentSafeGoal = null;
            rerouteCooldown = 0f;
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
