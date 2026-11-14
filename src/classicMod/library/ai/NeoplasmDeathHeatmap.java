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

/** Team-wide Neoplasm combat memory. Damage and deaths are tracked separately. */
public final class NeoplasmDeathHeatmap{
    private static final ObjectMap<Team, HeatGrid> grids = new ObjectMap<>();
    private static final ObjectMap<Team, Integer> revisions = new ObjectMap<>();
    private static final ObjectMap<Integer, Float> lastDeathByUnit = new ObjectMap<>();

    private static final int deathRadius = 16, impactRadius = 8;
    private static final float deathHeat = 132f, impactHeat = 38f;
    private static final float maxDeathHeat = 560f, maxImpactHeat = 150f, maxCombinedHeat = 640f;
    private static final float decayStepTicks = 15f;
    private static final float deathHalfLife = 210f * 60f, impactHalfLife = 48f * 60f;
    private static final float[] deathDecay = factors(deathHalfLife), impactDecay = factors(impactHalfLife);
    private static final float[][] deathFalloff = falloff(deathRadius, 2.1f);
    private static final float[][] impactFalloff = falloff(impactRadius, 1.7f);

    public static final float pathHeatWeight = 0.52f;
    public static final float directHeatWeight = 0.68f;
    private static boolean initialized;
    private static int worldWidth = -1, worldHeight = -1;

    private NeoplasmDeathHeatmap(){}

    private static float[] factors(float halfLife){
        int length = (int)(halfLife * 14f / decayStepTicks) + 1;
        float[] result = new float[length];
        for(int i = 0; i < length; i++) result[i] = (float)Math.pow(0.5d, (double)i * decayStepTicks / halfLife);
        return result;
    }

    private static float[][] falloff(int radius, float exponent){
        float[][] result = new float[radius * 2 + 1][radius * 2 + 1];
        for(int y = -radius; y <= radius; y++) for(int x = -radius; x <= radius; x++){
            float d = (float)Math.sqrt(x*x + y*y);
            if(d < radius) result[y + radius][x + radius] = (float)Math.pow(1f - d / radius, exponent);
        }
        return result;
    }

    public static void init(){
        if(initialized) return;
        initialized = true;
        Events.on(EventType.UnitDamageEvent.class, e -> recordImpact(e.unit));
        Events.on(EventType.UnitDestroyEvent.class, e -> recordDeath(e.unit));
        Events.on(EventType.UnitDrownEvent.class, e -> recordDeath(e.unit));
        Events.on(EventType.WorldLoadEvent.class, e -> clear());
        Events.on(EventType.ResetEvent.class, e -> clear());
    }

    public static boolean isSuicideBomber(Unit unit){
        if(unit == null) return false;
        return unit instanceof TimedKillc || unit.controller() instanceof SuicideAI
            || unit.type == RUnitTypes.hydroBomber || unit.type == UnitTypes.crawler;
    }

    public static boolean isNeoplasm(Unit unit){
        if(unit == null || isSuicideBomber(unit)) return false;
        var controller = unit.controller();
        if(controller instanceof NeoplasmAIController || controller instanceof NeoplasmFlyingAI
            || controller instanceof NeoplasmGroundAI) return true;
        if(unit.team == Team.neoplastic) return true;
        return unit.type == RUnitTypes.pallium || unit.type == RUnitTypes.miserable
            || unit.type == RUnitTypes.kalyx || unit.type == RUnitTypes.mule
            || unit.type == RUnitTypes.worm || unit.type == RUnitTypes.blob
            || unit.type == RUnitTypes.squid || unit.type == RUnitTypes.matte
            || unit.type == RUnitTypes.siegeCrawler || unit.type == RUnitTypes.sporeCarrier
            || unit.type == RUnitTypes.polyctenid || unit.type == RUnitTypes.scolopendra
            || unit.type == RUnitTypes.mycelid;
    }

    private static boolean suppressed(Unit unit){
        var c = unit.controller();
        if(c instanceof NeoplasmAIController ai) return ai.ignoreDeathHeat;
        if(c instanceof NeoplasmFlyingAI ai) return ai.isIgnoringDeathHeat();
        if(c instanceof NeoplasmGroundAI ai) return ai.isIgnoringDeathHeat();
        return false;
    }

    private static Tile tile(Unit unit){
        Tile t = unit.tileOn();
        if(t == null && Vars.world != null) t = Vars.world.tile(unit.tileX(), unit.tileY());
        return t;
    }

    private static void recordImpact(Unit unit){
        if(Vars.net.client() || !isNeoplasm(unit) || suppressed(unit)) return;
        Tile t = tile(unit);
        if(t == null) return;
        float severity = 0.8f + (1f - Math.max(0f, Math.min(1f, unit.healthf()))) * 0.8f;
        add(unit.team, t, impactHeat * severity, impactRadius, impactFalloff, maxImpactHeat, false);
        NeoplasmPerfMonitor.damageRecorded();
    }

    private static void recordDeath(Unit unit){
        if(Vars.net.client() || !isNeoplasm(unit) || suppressed(unit)) return;
        float now = Time.time, previous = lastDeathByUnit.get(unit.id, -1000f);
        if(now - previous < 2f) return;
        lastDeathByUnit.put(unit.id, now);
        Tile t = tile(unit);
        if(t == null) return;
        float scale = (1f + Math.min(0.55f, unit.hitSize / 64f)) * (1f + Math.min(0.45f, unit.maxHealth / 7000f));
        add(unit.team, t, deathHeat * scale, deathRadius, deathFalloff, maxDeathHeat, true);
        NeoplasmPerfMonitor.deathRecorded();
    }

    private static void ensureWorld(){
        if(Vars.world == null) return;
        int w = Vars.world.width(), h = Vars.world.height();
        if(w <= 0 || h <= 0) return;
        if(w != worldWidth || h != worldHeight){
            grids.clear(); revisions.clear(); lastDeathByUnit.clear();
            worldWidth = w; worldHeight = h;
        }
    }

    private static HeatGrid grid(Team team, boolean create){
        if(team == null || Vars.world == null) return null;
        ensureWorld();
        if(worldWidth <= 0 || worldHeight <= 0) return null;
        HeatGrid g = grids.get(team);
        if(g == null && create){ g = new HeatGrid(worldWidth * worldHeight); grids.put(team, g); }
        return g;
    }

    private static void add(Team team, Tile center, float amount, int radius, float[][] falloff, float cap, boolean death){
        HeatGrid g = grid(team, true);
        if(g == null) return;
        HeatLayer layer = death ? g.deaths : g.impacts;
        revisions.put(team, revisions.get(team, 0) + 1);
        float now = Time.time;
        for(int y = -radius; y <= radius; y++) for(int x = -radius; x <= radius; x++){
            Tile t = Vars.world.tile(center.x + x, center.y + y);
            if(t == null) continue;
            float weight = falloff[y + radius][x + radius];
            if(weight <= 0f) continue;
            int i = t.array();
            if(i < 0 || i >= layer.values.length) continue;
            float old = read(layer, i, now, death);
            if(!layer.tracked[i]){ layer.tracked[i] = true; layer.active.add(i); }
            layer.values[i] = Math.min(cap, old + amount * weight);
            layer.updated[i] = now;
        }
    }

    private static float read(HeatLayer l, int i, float now, boolean death){
        float value = l.values[i];
        if(value <= 0f) return 0f;
        int steps = (int)((now - l.updated[i]) / decayStepTicks);
        if(steps <= 0) return value;
        float[] table = death ? deathDecay : impactDecay;
        if(steps >= table.length){ value = 0f; l.updated[i] = now; }
        else{ value *= table[steps]; l.updated[i] += steps * decayStepTicks; }
        if(value < 0.01f) value = 0f;
        l.values[i] = value;
        return value;
    }

    public static float getHeat(Team team, Tile tile){
        long start = NeoplasmPerfMonitor.startTimer();
        try{
            if(tile == null) return 0f;
            HeatGrid g = grid(team, false);
            if(g == null) return 0f;
            int i = tile.array();
            if(i < 0 || i >= g.deaths.values.length) return 0f;
            return Math.min(maxCombinedHeat, read(g.deaths, i, Time.time, true) + read(g.impacts, i, Time.time, false));
        }finally{ NeoplasmPerfMonitor.endTimer(NeoplasmPerfMonitor.TIMER_HEAT, start); }
    }

    public static int getRevision(Team team){ return team == null ? 0 : revisions.get(team, 0); }

    public static void copyHeat(Team team, float[] output){
        if(output == null) return;
        java.util.Arrays.fill(output, 0f);
        HeatGrid g = grid(team, false);
        if(g == null) return;
        float now = Time.time;
        boolean expired = copy(g.deaths, output, now, true) | copy(g.impacts, output, now, false);
        if(expired) revisions.put(team, revisions.get(team, 0) + 1);
        for(int i = 0; i < output.length; i++) output[i] = Math.min(maxCombinedHeat, output[i]);
    }

    private static boolean copy(HeatLayer l, float[] out, float now, boolean death){
        boolean expired = false;
        for(int n = l.active.size - 1; n >= 0; n--){
            int i = l.active.get(n);
            if(i < 0 || i >= l.values.length){ l.active.removeIndex(n); continue; }
            if(i >= out.length) continue;
            float value = read(l, i, now, death);
            if(value <= 0.01f){
                l.values[i] = 0f; l.tracked[i] = false; l.active.removeIndex(n); expired = true;
            }else out[i] += value;
        }
        return expired;
    }

    public static void clear(){
        grids.clear(); revisions.clear(); lastDeathByUnit.clear();
        worldWidth = -1; worldHeight = -1;
        NeoplasmStrategicPathfinder.clear();
    }

    private static final class HeatGrid{
        final HeatLayer deaths, impacts;
        HeatGrid(int size){ deaths = new HeatLayer(size); impacts = new HeatLayer(size); }
    }

    private static final class HeatLayer{
        final float[] values, updated;
        final boolean[] tracked;
        final IntSeq active = new IntSeq();
        HeatLayer(int size){ values = new float[size]; updated = new float[size]; tracked = new boolean[size]; }
    }
}
