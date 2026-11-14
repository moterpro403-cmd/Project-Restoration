package classicMod.library.ai;

import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.Time;
import classicMod.content.RUnitTypes;
import classicMod.content.RVars;
import classicMod.library.GeometryPlus;
import classicMod.library.blocks.neoplasiaBlocks.CausticHeart;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.Units;
import mindustry.entities.units.AIController;
import mindustry.ai.types.SuicideAI;
import mindustry.gen.*;
import mindustry.game.Team;
import mindustry.world.Tile;

/**
 * Original Neoplasm group-based death avoidance.
 * Nearby Neoplasm deaths mark a small danger fringe; units follow the normal
 * flowfield but detour around those remembered tiles. No heatmap or Dijkstra map.
 */
public class NeoplasmAIController extends AIController {
    public final Seq<Tile> DodgeTile = new Seq<>();
    public final Seq<Unit> groups = new Seq<>();
    /** Suppress a danger marker for intentional removal, such as cord placement. */
    public boolean ignore;

    private final ObjectSet<Unit> knownNeoplasms = new ObjectSet<>();
    private final Seq<Unit> neoplasmGroup = new Seq<>();
    private final Seq<Unit> deadUnitBuffer = new Seq<>();
    private float discoveryTimer;

    @Override
    public void updateUnit() {
        updateNeoplasmTracking();
        super.updateUnit();
    }

    /** Discover nearby allies and remember an eight-tile ring around tracked deaths. */
    public void updateNeoplasmTracking() {
        if (unit == null || Vars.world == null || (Vars.net != null && Vars.net.client())) return;

        if (discoveryTimer <= 0f) {
            discoveryTimer = 10f;
            Unit neo = Units.closest(unit.team, unit.x, unit.y,
                    other -> other != unit && !knownNeoplasms.contains(other) && isNeoplasmUnit(other));
            if (neo != null) {
                knownNeoplasms.add(neo);
                neoplasmGroup.add(neo);
            }
        } else {
            discoveryTimer -= Time.delta;
        }

        deadUnitBuffer.clear();
        for (Unit ally : neoplasmGroup) {
            if (ally == null || !ally.dead) continue;
            Tile deadTile = ally.tileOn();
            if (deadTile != null && !ignoresDeathAvoidance(ally)) {
                for (Point2 point : GeometryPlus.d8plus) {
                    Tile danger = Vars.world.tile(deadTile.x + point.x, deadTile.y + point.y);
                    if (danger != null) DodgeTile.addUnique(danger);
                }
            }
            deadUnitBuffer.add(ally);
            knownNeoplasms.remove(ally);
        }
        neoplasmGroup.removeAll(deadUnitBuffer);
    }

    private static boolean ignoresDeathAvoidance(Unit ally) {
        Object controller = ally.controller();
        if (controller instanceof NeoplasmAIController ai) return ai.ignore;
        if (controller instanceof NeoplasmFlyingAI ai) return ai.isIgnoringDeathAvoidance();
        if (controller instanceof NeoplasmGroundAI ai) return ai.isIgnoringDeathAvoidance();
        if (controller instanceof NeoplasmHugAI ai) return ai.isIgnoringDeathAvoidance();
        return false;
    }

    public static boolean isNeoplasmUnit(Unit ally) {
        if (ally == null) return false;
        Object controller = ally.controller();
        if (controller instanceof NeoplasmAIController
                || controller instanceof NeoplasmFlyingAI
                || controller instanceof NeoplasmGroundAI
                || controller instanceof NeoplasmHugAI
                || controller instanceof HelperBlobAI) return true;
        if (ally.team == Team.neoplastic) return true;
        return ally.type == RUnitTypes.pallium || ally.type == RUnitTypes.miserable
                || ally.type == RUnitTypes.kalyx || ally.type == RUnitTypes.mule
                || ally.type == RUnitTypes.worm || ally.type == RUnitTypes.blob
                || ally.type == RUnitTypes.squid || ally.type == RUnitTypes.matte
                || ally.type == RUnitTypes.siegeCrawler || ally.type == RUnitTypes.sporeCarrier
                || ally.type == RUnitTypes.polyctenid || ally.type == RUnitTypes.scolopendra
                || ally.type == RUnitTypes.mycelid;
    }

    public static boolean isSuicideBomber(Unit other) {
        return other != null && (other instanceof TimedKillc
                || other.controller() instanceof SuicideAI
                || other.type == RUnitTypes.hydroBomber
                || other.type == UnitTypes.crawler);
    }

    @Nullable
    public Tile getClosestVent() {
        if (unit == null) return null;
        Tile closest = null;
        float bestDistance = Float.MAX_VALUE;
        for (Tile tile : PathfinderExtended.SteamVents) {
            if (tile == null || tile.build instanceof CausticHeart.HeartBuilding) continue;
            float distance = unit.dst2(tile.worldx(), tile.worldy());
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = tile;
            }
        }
        return closest;
    }

    @Nullable
    public Tile getClosestVent(boolean avoidDangerous) {
        if (!avoidDangerous) return getClosestVent();
        if (unit == null) return null;
        Tile closest = null;
        float bestDistance = Float.MAX_VALUE;
        for (Tile tile : PathfinderExtended.SteamVents) {
            if (tile == null || tile.build instanceof CausticHeart.HeartBuilding) continue;
            Building enemy = Units.findEnemyTile(unit.team, tile.worldx(), tile.worldy(), 240f, build -> !build.dead);
            if (enemy != null && tile.dst(enemy) <= 80f) continue;
            float distance = unit.dst2(tile.worldx(), tile.worldy());
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = tile;
            }
        }
        return closest;
    }

    @Nullable
    public Tile getClosestTarget(int range) {
        if (unit == null || Vars.world == null) return null;
        int mid = Math.max(1, range / 2);
        Tile best = null;
        float bestDistance = Float.MAX_VALUE;
        for (int y = -mid; y <= mid; y++) {
            for (int x = -mid; x <= mid; x++) {
                Tile candidate = Vars.world.tile(unit.tileX() + x, unit.tileY() + y);
                if (candidate == null || candidate.block() != Blocks.air
                        || candidate.floor() == null || candidate.floor().isLiquid) continue;
                float distance = target == null ? candidate.dst2(unit) : candidate.dst2(target);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** Nearest remembered death tile; larger values mean safer locations. */
    public float dangerDistance(Tile tile) {
        if (tile == null || DodgeTile.isEmpty()) return Float.MAX_VALUE;
        float nearest = Float.MAX_VALUE;
        for (Tile danger : DodgeTile) {
            if (danger != null) nearest = Math.min(nearest, danger.dst(tile));
        }
        return nearest;
    }

    @Nullable
    public Tile closestDanger() {
        if (unit == null) return null;
        Tile closest = null;
        float bestDistance = Float.MAX_VALUE;
        for (Tile candidate : DodgeTile) {
            if (candidate == null) continue;
            float distance = candidate.dst(unit);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = candidate;
            }
        }
        return closest;
    }

    @Nullable
    public Tile getClosestTarget(int range, Tile closestDanger, Tile targetTile, Unit movingUnit) {
        if (closestDanger == null || targetTile == null || movingUnit == null || Vars.world == null) return null;
        int mid = Math.max(1, range / 2);
        Tile best = null;
        float bestTargetDistance = Float.MAX_VALUE;
        for (int y = -mid; y <= mid; y++) {
            for (int x = -mid; x <= mid; x++) {
                Tile candidate = Vars.world.tile(movingUnit.tileX() + x, movingUnit.tileY() + y);
                if (candidate == null || candidate.block() != Blocks.air
                        || candidate.floor() == null || candidate.floor().isLiquid
                        || !movingUnit.canPass(candidate.x, candidate.y)) continue;
                if (closestDanger.dst(candidate) < 80f) continue;
                float unitDistance = movingUnit.dst(candidate);
                if (unitDistance < 10f || unitDistance > 100f) continue;
                float targetDistance = candidate.dst(targetTile);
                if (targetDistance < bestTargetDistance) {
                    bestTargetDistance = targetDistance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    @Nullable
    public Tile getEdgeEscapeTile() {
        if (unit == null || Vars.world == null) return null;
        Tile best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Tile danger : DodgeTile) {
            if (danger == null) continue;
            for (Point2 offset : Geometry.d8) {
                Tile candidate = Vars.world.tile(danger.x + offset.x, danger.y + offset.y);
                if (candidate == null || DodgeTile.contains(candidate)
                        || candidate.block() != Blocks.air || candidate.floor() == null
                        || candidate.floor().isLiquid || !unit.canPass(candidate.x, candidate.y)) continue;
                float distance = candidate.dst(unit);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** Detour direct movement when the unit is close to a remembered death zone. */
    public boolean avoidNearbyDanger(Tile destination) {
        if (unit == null || destination == null || isSuicideBomber(unit)) return false;
        Tile current = unit.tileOn();
        Tile danger = closestDanger();
        if (current == null || danger == null || danger.dst(current) >= 80f) return false;

        Tile safe = getClosestTarget(15, danger, destination, unit);
        if (safe == null) safe = getEdgeEscapeTile();
        if (safe == null || safe == current || !unit.canPass(safe.x, safe.y)) {
            DodgeTile.remove(danger);
            return false;
        }
        unit.movePref(vec.set(safe.worldx(), safe.worldy()).sub(unit).limit(unit.speed()));
        return true;
    }

    /** Follow the old flowfield while avoiding recorded death zones. */
    public boolean applyDodgePathfind(int pathTarget) {
        if (unit == null || isSuicideBomber(unit) || RVars.pathfinderCustom == null) return false;
        Tile current = unit.tileOn();
        if (current == null) return false;

        int costType = unit.type.flowfieldPathType;
        Tile next = RVars.pathfinderCustom.getTargetTileDodge(
                current, RVars.pathfinderCustom.getField(unit.team, costType, pathTarget), DodgeTile
        );
        Tile danger = closestDanger();
        if (danger != null && danger.dst(current) < 80f) {
            Tile safe = getClosestTarget(15, danger, next == null ? current : next, unit);
            next = safe != null ? safe : getEdgeEscapeTile();
            if (next == null) DodgeTile.remove(danger);
        }

        if (next == null || next == current || !unit.canPass(next.x, next.y)) return false;
        if (costType == 2 && (next.floor() == null || !next.floor().isLiquid)) return false;
        NeoplasmPerfMonitor.riskDecision();
        unit.movePref(vec.trns(unit.angleTo(next.worldx(), next.worldy()), unit.speed()));
        return true;
    }

    public void routeAir() {
        if (unit == null || target == null) return;
        Tile destination = target.tileOn();
        if (destination != null && avoidNearbyDanger(destination)) return;
        vec.set(target.getX() - unit.x, target.getY() - unit.y);
        if (!vec.isZero()) unit.movePref(vec.setLength(unit.speed()));
    }

    @Override
    public void pathfind(int pathTarget) {
        if (unit == null) return;
        if (applyDodgePathfind(pathTarget)) return;
        Tile current = unit.tileOn();
        if (current == null) return;
        Tile next = Vars.pathfinder.getTargetTile(
                current, Vars.pathfinder.getField(unit.team, unit.type.flowfieldPathType, pathTarget)
        );
        if (next != null && next != current && unit.canPass(next.x, next.y)
                && (unit.type.flowfieldPathType != 2 || (next.floor() != null && next.floor().isLiquid))) {
            unit.movePref(vec.trns(unit.angleTo(next.worldx(), next.worldy()), unit.speed()));
        }
    }
}