package classicMod.library.ai;

import arc.Events;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.Time;
import classicMod.content.RUnitTypes;
import classicMod.content.RVars;
import classicMod.library.blocks.neoplasiaBlocks.CausticHeart;
import mindustry.Vars;
import mindustry.ai.types.SuicideAI;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Tile;

/**
 * Lightweight, shared memory of recent Neoplasm deaths.
 *
 * Deaths are stored as discrete locations with a short lifetime; this is not a
 * heatmap and does not build a map-wide route. Each controller gets a small,
 * team-local snapshot and uses the normal Mindustry flowfield with local detours.
 */
public class NeoplasmAIController extends AIController {
    private static final int maxDeathMarks = 80;
    private static final float deathMemoryLifetime = 60f * 60f;
    private static final float deathMergeRadius = 2.5f;
    private static final float dangerTriggerRadius = 88f;
    private static final float dangerSafeRadius = 64f;
    private static final float pathfinderSafeRadius = 56f;

    private static final Seq<DeathMark> deathMarks = new Seq<>();
    private static boolean initialized;
    private static int memoryRevision;
    private static float nextPruneTime;

    private static class DeathMark {
        final Team team;
        final Tile tile;
        float expiresAt;

        DeathMark(Team team, Tile tile, float expiresAt) {
            this.team = team;
            this.tile = tile;
            this.expiresAt = expiresAt;
        }
    }

    /** Discrete remembered death locations for this unit's team. */
    public final Seq<Tile> DodgeTile = new Seq<>();
    /** Retained for compatibility with older Neoplasm controllers. */
    public final Seq<Unit> groups = new Seq<>();
    /** Suppress a marker for intentional removal, such as CausticCord placement. */
    public boolean ignore;

    private int syncedMemoryRevision = -1;
    private int syncedTeamId = -1;

    /** Register the death listener once. Called from ClassicMod.loadContent(). */
    public static void init() {
        if (initialized) return;
        initialized = true;

        Events.on(EventType.UnitDestroyEvent.class, event -> recordDeath(event.unit));
        Events.on(EventType.WorldLoadEvent.class, event -> clearDeathMemory());
        Events.on(EventType.ResetEvent.class, event -> clearDeathMemory());
    }

    private static void clearDeathMemory() {
        deathMarks.clear();
        nextPruneTime = 0f;
        memoryRevision++;
    }

    private static void recordDeath(Unit dead) {
        if (dead == null || Vars.world == null || isSuicideBomber(dead)
                || !isNeoplasmUnit(dead) || ignoresDeathAvoidance(dead)) return;

        Tile tile = dead.tileOn();
        if (tile == null) tile = Vars.world.tile(dead.tileX(), dead.tileY());
        if (tile == null) return;

        float expiresAt = Time.time + deathMemoryLifetime;

        // Nearby deaths refresh one location instead of creating a growing pile
        // of almost-identical markers during large battles.
        for (DeathMark mark : deathMarks) {
            if (mark.team == dead.team && mark.tile != null
                    && mark.tile.dst(tile) <= deathMergeRadius) {
                mark.expiresAt = expiresAt;
                memoryRevision++;
                return;
            }
        }

        if (deathMarks.size >= maxDeathMarks) {
            int oldest = 0;
            for (int i = 1; i < deathMarks.size; i++) {
                if (deathMarks.get(i).expiresAt < deathMarks.get(oldest).expiresAt) oldest = i;
            }
            deathMarks.remove(oldest);
        }

        deathMarks.add(new DeathMark(dead.team, tile, expiresAt));
        memoryRevision++;
    }

    private static boolean ignoresDeathAvoidance(Unit ally) {
        Object controller = ally.controller();
        if (controller instanceof NeoplasmAIController ai) return ai.ignore;
        if (controller instanceof NeoplasmFlyingAI ai) return ai.isIgnoringDeathAvoidance();
        if (controller instanceof NeoplasmGroundAI ai) return ai.isIgnoringDeathAvoidance();
        if (controller instanceof NeoplasmHugAI ai) return ai.isIgnoringDeathAvoidance();
        return false;
    }

    private static void pruneDeathMemory() {
        if (Time.time < nextPruneTime) return;
        nextPruneTime = Time.time + 30f;

        boolean removed = false;
        for (int i = deathMarks.size - 1; i >= 0; i--) {
            DeathMark mark = deathMarks.get(i);
            if (mark.tile == null || Time.time >= mark.expiresAt) {
                deathMarks.remove(i);
                removed = true;
            }
        }
        if (removed) memoryRevision++;
    }

    @Override
    public void updateUnit() {
        updateNeoplasmTracking();
        super.updateUnit();
    }

    /** Refresh this controller's bounded snapshot when a death is recorded or expires. */
    public void updateNeoplasmTracking() {
        if (unit == null || Vars.world == null || (Vars.net != null && Vars.net.client())) return;
        init();
        pruneDeathMemory();

        if (syncedMemoryRevision == memoryRevision && syncedTeamId == unit.team.id) return;

        DodgeTile.clear();
        for (DeathMark mark : deathMarks) {
            if (mark.team == unit.team && mark.tile != null) DodgeTile.add(mark.tile);
        }

        syncedMemoryRevision = memoryRevision;
        syncedTeamId = unit.team.id;
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
            Building enemy = mindustry.entities.Units.findEnemyTile(unit.team, tile.worldx(), tile.worldy(),
                    240f, build -> !build.dead);
            if (enemy != null && tile.dst(enemy) <= 80f) continue;
            float distance = unit.dst2(tile.worldx(), tile.worldy());
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = tile;
            }
        }
        return closest;
    }

    /** Distance to the nearest remembered death location. Larger means safer. */
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

    /**
     * Find a passable detour that clears every remembered death location.
     * If no fully safe tile is in range, choose the step that most improves clearance.
     */
    @Nullable
    public Tile getClosestTarget(int range, Tile closestDanger, Tile targetTile, Unit movingUnit) {
        if (targetTile == null || movingUnit == null || Vars.world == null) return null;

        Tile current = movingUnit.tileOn();
        if (current == null) return null;

        int radius = Math.max(2, range / 2);
        float currentClearance = dangerDistance(current);
        Tile bestSafe = null, bestEscape = null;
        float bestSafeScore = Float.MAX_VALUE;
        float bestEscapeScore = Float.MAX_VALUE;
        boolean naval = movingUnit.type.flowfieldPathType == PathfinderCustom.costNaval;

        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                Tile candidate = Vars.world.tile(movingUnit.tileX() + x, movingUnit.tileY() + y);
                if (candidate == null || candidate == current || candidate.block() != Blocks.air
                        || candidate.floor() == null || !movingUnit.canPass(candidate.x, candidate.y)) continue;
                if (naval ? !candidate.floor().isLiquid : candidate.floor().isLiquid) continue;

                float clearance = dangerDistance(candidate);
                float targetDistance = candidate.dst(targetTile);
                float travelDistance = movingUnit.dst(candidate);

                if (clearance > dangerSafeRadius) {
                    float score = targetDistance + travelDistance * 0.55f
                            - Math.min(clearance - dangerSafeRadius, 80f) * 0.08f;
                    if (score < bestSafeScore) {
                        bestSafeScore = score;
                        bestSafe = candidate;
                    }
                } else if (clearance > currentClearance + 0.5f) {
                    // Emergency movement: prioritize getting farther from danger,
                    // but use the intended destination to break ties.
                    float improvement = clearance - currentClearance;
                    float score = -improvement * 2.5f + targetDistance * 0.025f
                            + travelDistance * 0.15f;
                    if (score < bestEscapeScore) {
                        bestEscapeScore = score;
                        bestEscape = candidate;
                    }
                }
            }
        }

        return bestSafe != null ? bestSafe : bestEscape;
    }

    @Nullable
    public Tile getEdgeEscapeTile() {
        Tile danger = closestDanger();
        Tile current = unit == null ? null : unit.tileOn();
        return danger == null || current == null ? null
                : getClosestTarget(19, danger, current, unit);
    }

    /** Detour direct movement before it enters a recently lethal area. */
    public boolean avoidNearbyDanger(Tile destination) {
        if (unit == null || destination == null || isSuicideBomber(unit)) return false;
        Tile current = unit.tileOn();
        if (current == null || dangerDistance(current) >= dangerTriggerRadius) return false;

        Tile safe = getClosestTarget(19, closestDanger(), destination, unit);
        // Holding position is safer than blindly re-entering a remembered death zone.
        if (safe == null) return true;

        unit.movePref(vec.set(safe.worldx(), safe.worldy()).sub(unit).limit(unit.speed()));
        return true;
    }

    /** Follow the normal flowfield, but step around recent death locations. */
    public boolean applyDodgePathfind(int pathTarget) {
        if (unit == null || isSuicideBomber(unit) || RVars.pathfinderCustom == null) return false;
        Tile current = unit.tileOn();
        if (current == null) return false;

        int costType = unit.type.flowfieldPathType;
        Tile next = RVars.pathfinderCustom.getTargetTileDodge(
                current, RVars.pathfinderCustom.getField(unit.team, costType, pathTarget), DodgeTile
        );

        if (dangerDistance(current) < dangerTriggerRadius) {
            Tile safe = getClosestTarget(19, closestDanger(), next == null ? current : next, unit);
            if (safe == null) return true;
            next = safe;
        }

        if (next == null || next == current || !unit.canPass(next.x, next.y)) return false;
        if (costType == PathfinderCustom.costNaval
                && (next.floor() == null || !next.floor().isLiquid)) return false;

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
                && (unit.type.flowfieldPathType != PathfinderCustom.costNaval
                || (next.floor() != null && next.floor().isLiquid))) {
            unit.movePref(vec.trns(unit.angleTo(next.worldx(), next.worldy()), unit.speed()));
        }
    }
}