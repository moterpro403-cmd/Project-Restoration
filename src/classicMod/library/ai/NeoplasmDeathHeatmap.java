package classicMod.library.ai;

import arc.Events;
import arc.struct.IntSeq;
import arc.struct.ObjectMap;
import arc.util.Time;
import classicMod.content.RUnitTypes;
import mindustry.Vars;
import mindustry.ai.types.SuicideAI;
import mindustry.content.UnitTypes;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.TimedKillc;
import mindustry.gen.Unit;
import mindustry.world.Tile;

/**
 * Team-wide memory of Neoplasm unit deaths.
 *
 * Each non-suicide death adds a danger gradient around its tile so subsequent
 * ground, naval, and flying units learn where fortified chokepoints and
 * killzones are located and route around them via safer, open paths.
 */
public final class NeoplasmDeathHeatmap {
    private static final ObjectMap<Team, HeatGrid> grids = new ObjectMap<>();
    private static final ObjectMap<Team, Integer> revisions = new ObjectMap<>();

    private static final int radius = 12;
    private static final float deathHeat = 85f;
    private static final float maxHeat = 450f;
    /** 4 minutes in simulation ticks (60 ticks/sec). */
    private static final float halfLifeTicks = 240f * 60f;
    private static final float decayStepTicks = 15f; // 0.25s in ticks
    private static final int maxDecaySteps = (int)(halfLifeTicks * 12f / decayStepTicks);
    private static final float[] decayFactors = new float[maxDecaySteps + 1];
    private static final float[][] deathFalloff = new float[radius * 2 + 1][radius * 2 + 1];

    static {
        for (int i = 0; i <= maxDecaySteps; i++) {
            decayFactors[i] = (float)Math.pow(0.5d, (double)i * decayStepTicks / halfLifeTicks);
        }
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                float distance = (float)Math.sqrt(dx * dx + dy * dy);
                if (distance <= radius) {
                    float falloff = 1f - distance / (radius + 1f);
                    // Broad plateau near the killzone center with smooth outer falloff
                    deathFalloff[dy + radius][dx + radius] = 0.25f + 0.75f * (falloff * falloff);
                }
            }
        }
    }

    /** Cost added to flowfield weight per point of heat. */
    public static final float pathHeatWeight = 0.65f;
    /** Cost added to direct-movement distance per point of heat. */
    public static final float directHeatWeight = 0.65f;

    private static boolean initialized;
    private static int worldWidth = -1;
    private static int worldHeight = -1;

    private NeoplasmDeathHeatmap() {
    }

    /** Register global death and world-reset hooks once. */
    public static void init() {
        if (initialized) return;
        initialized = true;

        Events.on(EventType.UnitDestroyEvent.class, event -> recordDeath(event.unit));
        Events.on(EventType.WorldLoadEvent.class, event -> clear());
        Events.on(EventType.ResetEvent.class, event -> clear());
    }

    /**
     * Returns true for kamikaze/suicide bomber units that are intentionally
     * meant to charge straight into fortified walls and chokepoints.
     */
    public static boolean isSuicideBomber(Unit unit) {
        if (unit == null) return false;
        if (unit instanceof TimedKillc) return true;
        if (unit.controller() instanceof SuicideAI) return true;
        return unit.type == RUnitTypes.hydroBomber
                || unit.type == UnitTypes.crawler;
    }

    /**
     * Returns true if the unit belongs to the Neoplasm assault/support roster
     * and should participate in death-heatmap learning and chokepoint avoidance.
     */
    public static boolean isNeoplasm(Unit unit) {
        if (unit == null || isSuicideBomber(unit)) return false;

        var controller = unit.controller();
        if (controller instanceof NeoplasmAIController
                || controller instanceof NeoplasmFlyingAI
                || controller instanceof NeoplasmGroundAI) {
            return true;
        }

        if (unit.team == Team.neoplastic) return true;

        return unit.type == RUnitTypes.pallium
                || unit.type == RUnitTypes.miserable
                || unit.type == RUnitTypes.kalyx
                || unit.type == RUnitTypes.mule
                || unit.type == RUnitTypes.worm
                || unit.type == RUnitTypes.blob
                || unit.type == RUnitTypes.squid
                || unit.type == RUnitTypes.matte
                || unit.type == RUnitTypes.siegeCrawler
                || unit.type == RUnitTypes.sporeCarrier
                || unit.type == RUnitTypes.polyctenid
                || unit.type == RUnitTypes.scolopendra
                || unit.type == RUnitTypes.mycelid;
    }

    private static boolean isDeathHeatSuppressed(Unit unit) {
        var controller = unit.controller();
        if (controller instanceof NeoplasmAIController ai) return ai.ignoreDeathHeat;
        if (controller instanceof NeoplasmFlyingAI ai) return ai.isIgnoringDeathHeat();
        if (controller instanceof NeoplasmGroundAI ai) return ai.isIgnoringDeathHeat();
        return false;
    }

    private static void recordDeath(Unit unit) {
        if (!isNeoplasm(unit)) return;

        if (isDeathHeatSuppressed(unit)) {
            NeoplasmPerfMonitor.deathSuppressed();
            return;
        }

        Tile tile = unit.tileOn();
        if (tile == null && Vars.world != null) {
            tile = Vars.world.tile(unit.tileX(), unit.tileY());
        }
        if (tile == null) return;

        addDeathHeat(unit.team, tile);
        NeoplasmPerfMonitor.deathRecorded();
    }

    private static void ensureWorldSize() {
        if (Vars.world == null) return;

        int width = Vars.world.width();
        int height = Vars.world.height();
        if (width <= 0 || height <= 0) return;

        if (width != worldWidth || height != worldHeight) {
            grids.clear();
            revisions.clear();
            worldWidth = width;
            worldHeight = height;
        }
    }

    private static HeatGrid grid(Team team, boolean create) {
        if (team == null || Vars.world == null) return null;
        ensureWorldSize();
        if (worldWidth <= 0 || worldHeight <= 0) return null;

        HeatGrid result = grids.get(team);
        if (result == null && create) {
            result = new HeatGrid(worldWidth, worldHeight);
            grids.put(team, result);
        }
        return result;
    }

    private static void bumpRevision(Team team) {
        revisions.put(team, revisions.get(team, 0) + 1);
    }

    private static void addDeathHeat(Team team, Tile center) {
        HeatGrid heat = grid(team, true);
        if (heat == null) return;

        bumpRevision(team);
        float now = Time.time;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                Tile tile = Vars.world.tile(center.x + dx, center.y + dy);
                if (tile == null) continue;

                float falloff = deathFalloff[dy + radius][dx + radius];
                if (falloff <= 0f) continue;

                int index = tile.array();
                if (index < 0 || index >= heat.values.length) continue;

                float previous = readHeat(heat, index, now);
                if (!heat.tracked[index]) {
                    heat.tracked[index] = true;
                    heat.active.add(index);
                }
                heat.values[index] = Math.min(maxHeat, previous + deathHeat * falloff);
                heat.updated[index] = now;
            }
        }
    }

    /** Returns the current decayed danger at a tile for this team's units. */
    public static float getHeat(Team team, Tile tile) {
        long perfStart = NeoplasmPerfMonitor.startTimer();
        try {
            if (tile == null) return 0f;

            HeatGrid heat = grid(team, false);
            if (heat == null) return 0f;

            int index = tile.array();
            if (index < 0 || index >= heat.values.length) return 0f;
            return readHeat(heat, index, Time.time);
        } finally {
            NeoplasmPerfMonitor.endTimer(NeoplasmPerfMonitor.TIMER_HEAT, perfStart);
        }
    }

    /** Monotonic team-local version used to refresh cached strategic routes. */
    public static int getRevision(Team team) {
        return team == null ? 0 : revisions.get(team, 0);
    }

    /** Copies the current, time-decayed danger field into a reusable tile-sized array. */
    public static void copyHeat(Team team, float[] output) {
        if (output == null) return;
        HeatGrid heat = grid(team, false);
        if (heat == null) {
            java.util.Arrays.fill(output, 0f);
            return;
        }

        java.util.Arrays.fill(output, 0f);
        float now = Time.time;
        int limit = Math.min(output.length, heat.values.length);
        boolean expiredAny = false;

        for (int i = heat.active.size - 1; i >= 0; i--) {
            int index = heat.active.get(i);
            if (index < 0 || index >= limit) {
                if (index >= 0 && index < heat.tracked.length) {
                    heat.tracked[index] = false;
                }
                heat.active.removeIndex(i);
                continue;
            }

            float value = readHeat(heat, index, now);
            if (value <= 0f) {
                heat.tracked[index] = false;
                heat.active.removeIndex(i);
                expiredAny = true;
            } else {
                output[index] = value;
            }
        }

        if (expiredAny) {
            bumpRevision(team);
        }
    }

    private static float readHeat(HeatGrid heat, int index, float now) {
        float value = heat.values[index];
        if (value <= 0f) return 0f;

        float last = heat.updated[index];
        if (last <= 0f) {
            heat.updated[index] = now;
            return value;
        }

        float elapsed = now - last;
        if (elapsed < decayStepTicks) return value;

        int step = (int)(elapsed / decayStepTicks);
        if (step >= maxDecaySteps) {
            value = 0f;
            heat.updated[index] = now;
        } else {
            value *= decayFactors[step];
            heat.updated[index] = last + step * decayStepTicks;
        }

        if (value < 0.25f) {
            value = 0f;
        }

        heat.values[index] = value;
        return value;
    }

    /** Clear all team heat when a new world is loaded or the game resets. */
    public static void clear() {
        grids.clear();
        revisions.clear();
        worldWidth = -1;
        worldHeight = -1;
        NeoplasmStrategicPathfinder.clear();
    }

    private static final class HeatGrid {
        final float[] values;
        final float[] updated;
        final boolean[] tracked;
        final IntSeq active = new IntSeq();

        HeatGrid(int width, int height) {
            int size = width * height;
            values = new float[size];
            updated = new float[size];
            tracked = new boolean[size];
        }
    }
}
