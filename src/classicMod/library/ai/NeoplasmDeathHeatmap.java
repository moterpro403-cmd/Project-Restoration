package classicMod.library.ai;

import arc.Events;
import arc.struct.IntSeq;
import arc.struct.ObjectMap;
import arc.util.Time;
import classicMod.content.RUnitTypes;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.world.Tile;

/**
 * Team-wide memory of Neoplasm unit deaths.
 *
 * Each death adds a soft danger gradient around its tile. All Neoplasm AI
 * controllers on that team read the same field, and old danger fades over time.
 */
public final class NeoplasmDeathHeatmap {
    private static final ObjectMap<Team, HeatGrid> grids = new ObjectMap<>();
    private static final ObjectMap<Team, Integer> revisions = new ObjectMap<>();

    private static final int radius = 9;
    private static final float deathHeat = 55f;
    private static final float maxHeat = 300f;
    private static final long halfLifeMillis = 180000L;
    private static final int decayStepMillis = 250;
    private static final int maxDecaySteps = (int)(halfLifeMillis * 12L / decayStepMillis);
    private static final float[] decayFactors = new float[maxDecaySteps + 1];
    private static final float[][] deathFalloff = new float[radius * 2 + 1][radius * 2 + 1];

    static {
        for(int i = 0; i <= maxDecaySteps; i++){
            decayFactors[i] = (float)Math.pow(0.5d, (double)i * decayStepMillis / halfLifeMillis);
        }
        for(int dy = -radius; dy <= radius; dy++){
            for(int dx = -radius; dx <= radius; dx++){
                float distance = (float)Math.sqrt(dx * dx + dy * dy);
                if(distance <= radius){
                    float falloff = 1f - distance / (radius + 1f);
                    deathFalloff[dy + radius][dx + radius] = falloff * falloff;
                }
            }
        }
    }

    /** Cost added to flowfield weight per point of heat. */
    public static final float pathHeatWeight = 0.24f;
    /** Cost added to direct-movement distance per point of heat. */
    public static final float directHeatWeight = 0.24f;

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

    private static boolean isNeoplasm(Unit unit) {
        if (unit == null) return false;
        if (unit.controller() instanceof NeoplasmAIController) return true;

        return unit.type == RUnitTypes.pallium
            || unit.type == RUnitTypes.miserable
            || unit.type == RUnitTypes.hydroBomber
            || unit.type == RUnitTypes.kalyx
            || unit.type == RUnitTypes.mule
            || unit.type == RUnitTypes.worm
            || unit.type == RUnitTypes.blob
            || unit.type == RUnitTypes.squid
            || unit.type == RUnitTypes.matte;
    }

    private static void recordDeath(Unit unit) {
        if (!isNeoplasm(unit)) return;

        // Only suppress intentional self-removal. The legacy 'ignore' flag is
        // also used by combat AI (notably suicide bombers attacking buildings).
        if (unit.controller() instanceof NeoplasmAIController ai && ai.ignoreDeathHeat) {
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

    private static void addDeathHeat(Team team, Tile center) {
        HeatGrid heat = grid(team, true);
        if (heat == null) return;

        revisions.put(team, revisions.get(team, 0) + 1);
        long now = Time.millis();
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                Tile tile = Vars.world.tile(center.x + dx, center.y + dy);
                if (tile == null) continue;

                float falloff = deathFalloff[dy + radius][dx + radius];
                if(falloff <= 0f) continue;

                int index = tile.array();
                float previous = readHeat(heat, index, now);
                if(!heat.tracked[index]){
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
        try{
            if (tile == null) return 0f;

            HeatGrid heat = grid(team, false);
            if (heat == null) return 0f;

            int index = tile.array();
            if (index < 0 || index >= heat.values.length) return 0f;
            return readHeat(heat, index, Time.millis());
        }finally{
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
        long now = Time.millis();
        int limit = Math.min(output.length, heat.values.length);
        // Only visit cells that have received death heat. Most maps have a
        // very sparse danger field, so avoid a pow() call for every world tile.
        for(int i = heat.active.size - 1; i >= 0; i--){
            int index = heat.active.get(i);
            if(index < 0 || index >= limit) continue;
            float value = readHeat(heat, index, now);
            if(value <= 0f){
                heat.tracked[index] = false;
                heat.active.removeIndex(i);
            }else{
                output[index] = value;
            }
        }
    }

    private static float readHeat(HeatGrid heat, int index, long now) {
        float value = heat.values[index];
        if (value <= 0f) return 0f;

        long last = heat.updated[index];
        if (last <= 0L) return value;

        long elapsed = Math.max(0L, now - last);
        if(elapsed < decayStepMillis / 2) return value;

        int step = (int)((elapsed + decayStepMillis / 2) / decayStepMillis);
        if(step >= maxDecaySteps){
            value = 0f;
        }else if(step > 0){
            value *= decayFactors[step];
        }
        if(value < 0.25f) value = 0f;

        heat.values[index] = value;
        heat.updated[index] = now;
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
        final long[] updated;
        final boolean[] tracked;
        final IntSeq active = new IntSeq();

        HeatGrid(int width, int height) {
            int size = width * height;
            values = new float[size];
            updated = new long[size];
            tracked = new boolean[size];
        }
    }
}
