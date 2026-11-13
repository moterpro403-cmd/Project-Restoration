package classicMod.library.ai;

import arc.Events;
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

    private static final int radius = 9;
    private static final float deathHeat = 90f;
    private static final float maxHeat = 100f;
    private static final long halfLifeMillis = 60000L;

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

        // Ignore units that intentionally remove themselves after placing cord.
        if (unit.controller() instanceof NeoplasmAIController ai && ai.ignore) return;

        Tile tile = unit.tileOn();
        if (tile == null && Vars.world != null) {
            tile = Vars.world.tile(unit.tileX(), unit.tileY());
        }
        if (tile == null) return;

        addDeathHeat(unit.team, tile);
    }

    private static void ensureWorldSize() {
        if (Vars.world == null) return;

        int width = Vars.world.width();
        int height = Vars.world.height();
        if (width <= 0 || height <= 0) return;

        if (width != worldWidth || height != worldHeight) {
            grids.clear();
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

        long now = Time.millis();
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                Tile tile = Vars.world.tile(center.x + dx, center.y + dy);
                if (tile == null) continue;

                float distance = (float)Math.sqrt(dx * dx + dy * dy);
                if (distance > radius) continue;

                float falloff = 1f - distance / (radius + 1f);
                float addition = deathHeat * falloff * falloff;
                int index = tile.array();
                float previous = readHeat(heat, index, now);

                heat.values[index] = Math.min(maxHeat, previous + addition);
                heat.updated[index] = now;
            }
        }
    }

    /** Returns the current decayed danger at a tile for this team's units. */
    public static float getHeat(Team team, Tile tile) {
        if (tile == null) return 0f;

        HeatGrid heat = grid(team, false);
        if (heat == null) return 0f;

        int index = tile.array();
        if (index < 0 || index >= heat.values.length) return 0f;
        return readHeat(heat, index, Time.millis());
    }

    private static float readHeat(HeatGrid heat, int index, long now) {
        float value = heat.values[index];
        if (value <= 0f) return 0f;

        long last = heat.updated[index];
        if (last <= 0L) return value;

        long elapsed = Math.max(0L, now - last);
        if (elapsed == 0L) return value;

        value *= (float)Math.pow(0.5d, (double)elapsed / halfLifeMillis);
        if (value < 0.25f) value = 0f;

        heat.values[index] = value;
        heat.updated[index] = now;
        return value;
    }

    /** Clear all team heat when a new world is loaded or the game resets. */
    public static void clear() {
        grids.clear();
        worldWidth = -1;
        worldHeight = -1;
    }

    private static final class HeatGrid {
        final float[] values;
        final long[] updated;

        HeatGrid(int width, int height) {
            int size = width * height;
            values = new float[size];
            updated = new long[size];
        }
    }
}
