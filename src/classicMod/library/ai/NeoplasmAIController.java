package classicMod.library.ai;

import arc.Events;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.struct.ObjectMap;
import arc.util.Nullable;
import arc.util.Time;
import classicMod.content.RUnitTypes;
import classicMod.content.RVars;
import classicMod.library.blocks.neoplasiaBlocks.CausticHeart;
import mindustry.Vars;
import mindustry.ai.types.SuicideAI;
import mindustry.content.UnitTypes;
import mindustry.entities.Units;
import mindustry.entities.units.AIController;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Tile;

/**
 * Shared, bounded memory of recent Neoplasm casualties.
 *
 * The memory is a team-local tile grid, not a map-wide heatmap or route solver.
 * Normal movement still follows Mindustry flowfields. Danger is only consulted
 * when choosing a nearby next step or steering a direct-movement unit.
 */
public class NeoplasmAIController extends AIController {
    private static final float deathMemoryLifetime = 60f * 60f;
    private static final int minimumDeathRadius = 3;
    private static final int maximumDeathRadius = 7;
    private static final float dangerLookAheadTiles = 8f;
    private static final float dangerTrigger = 0.045f;
    private static final float pathRiskWeight = 36f;
    private static final float directRiskWeight = 34f;
    private static final float minimumRiskImprovement = 0.025f;

    private static final ObjectMap<Team, DangerField> fields = new ObjectMap<>();
    private static boolean initialized;
    private static int worldWidth, worldHeight;

    private static class DangerField {
        /** Expiry time per tile. Zero means the cell has never been marked. */
        final float[] expiresAt;
        /** Unsigned 0..255 risk intensity per tile. */
        final byte[] risk;

        DangerField(int length) {
            expiresAt = new float[length];
            risk = new byte[length];
        }
    }

    /** Suppress a death record for intentional removal, such as CausticCord placement. */
    public boolean ignore;
    /** Per-archetype tuning. Higher values favor safer detours. */
    public float dangerSensitivity = 1f;

    /** Register the shared death listener once. Called from ClassicMod.loadContent(). */
    public static void initializeDeathMemory() {
        if (initialized) return;
        initialized = true;

        Events.on(EventType.UnitDestroyEvent.class, event -> recordDeath(event.unit));
        Events.on(EventType.WorldLoadEvent.class, event -> clearDeathMemory());
        Events.on(EventType.ResetEvent.class, event -> clearDeathMemory());
    }

    private static void clearDeathMemory() {
        fields.clear();
        if (Vars.world != null) {
            worldWidth = Vars.world.width();
            worldHeight = Vars.world.height();
        } else {
            worldWidth = worldHeight = 0;
        }
    }

    private static void ensureWorldSize() {
        if (Vars.world == null) return;
        int width = Vars.world.width();
        int height = Vars.world.height();
        if (width <= 0 || height <= 0) return;

        if (width != worldWidth || height != worldHeight) {
            fields.clear();
            worldWidth = width;
            worldHeight = height;
        }
    }

    @Nullable
    private static DangerField field(Team team, boolean create) {
        if (team == null || Vars.world == null) return null;
        ensureWorldSize();
        if (worldWidth <= 0 || worldHeight <= 0) return null;

        DangerField result = fields.get(team);
        if (result == null && create) {
            result = new DangerField(worldWidth * worldHeight);
            fields.put(team, result);
        }
        return result;
    }

    private static void recordDeath(Unit dead) {
        if (dead == null || Vars.world == null || isSuicideBomber(dead)
                || !isNeoplasmUnit(dead) || ignoresDeathAvoidance(dead)) return;

        Tile tile = dead.tileOn();
        if (tile == null) tile = Vars.world.tile(dead.tileX(), dead.tileY());
        if (tile == null) return;

        DangerField grid = field(dead.team, true);
        if (grid == null) return;

        int radius = Mathf.clamp(
                minimumDeathRadius + Mathf.round(dead.hitSize / Vars.tilesize * 0.5f),
                minimumDeathRadius, maximumDeathRadius
        );
        int centerX = tile.x, centerY = tile.y;
        float now = Time.time;
        float expires = now + deathMemoryLifetime;
        float radius2 = radius * radius;

        // Rasterize a compact local influence around the casualty. Nearby deaths
        // merge naturally by keeping the strongest risk and latest expiry.
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                float distance2 = dx * dx + dy * dy;
                if (distance2 > radius2) continue;

                int x = centerX + dx, y = centerY + dy;
                if (x < 0 || y < 0 || x >= worldWidth || y >= worldHeight) continue;

                int index = x + y * worldWidth;
                float falloff = 1f - Mathf.sqrt(distance2) / (radius + 0.25f);
                int intensity = Mathf.clamp(Mathf.round((0.10f + falloff * 0.90f) * 255f), 0, 255);

                if (grid.expiresAt[index] <= now) {
                    grid.risk[index] = (byte)intensity;
                } else {
                    grid.risk[index] = (byte)Math.max(grid.risk[index] & 0xff, intensity);
                }
                grid.expiresAt[index] = Math.max(grid.expiresAt[index], expires);
            }
        }
    }

    private static boolean ignoresDeathAvoidance(Unit ally) {
        Object controller = ally.controller();
        if (controller instanceof NeoplasmAIController ai) return ai.ignore;
        if (controller instanceof NeoplasmFlyingAI ai) return ai.isIgnoringDeathAvoidance();
        if (controller instanceof NeoplasmGroundAI ai) return ai.isIgnoringDeathAvoidance();
        if (controller instanceof NeoplasmHugAI ai) return ai.isIgnoringDeathAvoidance();
        return false;
    }

    /** O(1) risk lookup. Expired cells are cleared lazily when they are queried. */
    public static float getDangerRisk(Team team, Tile tile) {
        if (tile == null) return 0f;
        DangerField grid = field(team, false);
        if (grid == null) return 0f;

        int index = tile.x + tile.y * worldWidth;
        if (index < 0 || index >= grid.risk.length) return 0f;
        if (grid.expiresAt[index] <= Time.time) {
            grid.expiresAt[index] = 0f;
            grid.risk[index] = 0;
            return 0f;
        }
        return (grid.risk[index] & 0xff) / 255f;
    }

    public float getDangerRisk(Tile tile) {
        return unit == null ? 0f : getDangerRisk(unit.team, tile);
    }

    @Override
    public void updateUnit() {
        updateNeoplasmTracking();
        super.updateUnit();
    }

    /**
     * Kept for existing controllers. Unlike the previous implementation, this
     * does not copy a death list into every unit controller.
     */
    public void updateNeoplasmTracking() {
        if (unit == null || Vars.world == null || (Vars.net != null && Vars.net.client())) return;
        ensureWorldSize();
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

    private static float sampleRiskAhead(Team team, Tile from, Tile destination, int maxSteps) {
        if (from == null || destination == null) return 0f;

        float dx = destination.x - from.x;
        float dy = destination.y - from.y;
        float distance = Mathf.sqrt(dx * dx + dy * dy);
        float risk = getDangerRisk(team, from);
        if (distance <= 0.001f) return risk;

        int steps = Math.min(maxSteps, Math.max(1, Mathf.ceil(distance)));
        float dirX = dx / distance, dirY = dy / distance;
        for (int step = 1; step <= steps; step++) {
            int x = Mathf.round(from.x + dirX * step);
            int y = Mathf.round(from.y + dirY * step);
            Tile sample = Vars.world.tile(x, y);
            if (sample != null) {
                risk = Math.max(risk, getDangerRisk(team, sample));
            }
        }
        return risk;
    }

    /**
     * Steer direct-movement units around the local edge of a remembered danger
     * area. The check is limited to eight tiles ahead and eight adjacent choices.
     */
    public boolean avoidNearbyDanger(Tile destination) {
        if (unit == null || destination == null || Vars.world == null
                || isSuicideBomber(unit)) return false;

        Tile current = unit.tileOn();
        if (current == null) return false;

        float currentRisk = getDangerRisk(unit.team, current);
        float directRisk = sampleRiskAhead(unit.team, current, destination,
                Mathf.ceil(dangerLookAheadTiles));

        if (currentRisk < dangerTrigger && directRisk < dangerTrigger) return false;

        Tile best = null;
        float bestRisk = Float.MAX_VALUE;
        float bestScore = Float.MAX_VALUE;

        for (Point2 direction : Geometry.d8) {
            Tile candidate = Vars.world.tile(current.x + direction.x, current.y + direction.y);
            if (candidate == null || candidate == current) continue;
            if (!unit.type.flying && !unit.canPass(candidate.x, candidate.y)) continue;

            float risk = sampleRiskAhead(unit.team, candidate, destination,
                    Mathf.ceil(dangerLookAheadTiles));
            float distance = candidate.dst(destination);
            float score = distance + risk * directRiskWeight * dangerSensitivity;

            if (score < bestScore) {
                bestScore = score;
                bestRisk = risk;
                best = candidate;
            }
        }

        if (best == null) return false;

        boolean leavesDanger = currentRisk >= dangerTrigger
                && bestRisk + minimumRiskImprovement < currentRisk;
        boolean avoidsRoute = directRisk >= dangerTrigger
                && bestRisk + minimumRiskImprovement < directRisk;

        if (!leavesDanger && !avoidsRoute) return false;

        unit.movePref(vec.set(best.worldx(), best.worldy()).sub(unit).limit(unit.speed()));
        NeoplasmPerfMonitor.riskDecision();
        return true;
    }

    /** Apply local death-risk steering on top of the normal shared flowfield. */
    public boolean applyDodgePathfind(int pathTarget) {
        return applyDodgePathfind(pathTarget, false);
    }

    /** @param stuckAvoidance use vanilla's unit-congestion avoidance when GroundAI detects a jam. */
    public boolean applyDodgePathfind(int pathTarget, boolean stuckAvoidance) {
        if (unit == null || isSuicideBomber(unit) || RVars.pathfinderCustom == null) return false;

        Tile current = unit.tileOn();
        if (current == null) return false;

        int costType = unit.type.flowfieldPathType;
        Tile next = RVars.pathfinderCustom.getTargetTileDodge(
                current,
                RVars.pathfinderCustom.getField(unit.team, costType, pathTarget),
                unit,
                stuckAvoidance,
                dangerSensitivity
        );

        if (next == null || next == current || !unit.canPass(next.x, next.y)) return false;
        if (costType == PathfinderCustom.costNaval
                && (next.floor() == null || !next.floor().isLiquid)) return false;

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
        if (!applyDodgePathfind(pathTarget, false)) super.pathfind(pathTarget);
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
            Building enemy = Units.findEnemyTile(unit.team, tile.worldx(), tile.worldy(),
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
}