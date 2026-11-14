package classicMod.library.ai;

import arc.Core;
import arc.Events;
import arc.util.Log;
import arc.util.Time;
import classicMod.content.RUnitTypes;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

/**
 * Optional ten-second snapshot of Neoplasm workload, elapsed AI subsystem time,
 * and JVM heap usage. Enable "Neoplasm performance profiler" in settings.
 */
public final class NeoplasmPerfMonitor {
    public static final int TIMER_PATH = 0;
    public static final int TIMER_STEERING = 1;
    public static final int TIMER_TURRET = 4;
    public static final int TIMER_TRAFFIC = 5;
    public static final int TIMER_CORD = 6;
    public static final int TIMER_CORD_SCAN = 7;
    public static final int TIMER_REPAIR = 8;

    private static final long[] timerNanos = new long[9];
    private static final int[] timerCalls = new int[9];

    private static boolean initialized;
    private static boolean enabled;
    private static float nextSettingCheck;
    private static float nextReport;

    private static int pathRequests;
    private static int riskDecisions;
    private static int turretScans;
    private static int trafficScans;
    private static int cordScans;
    private static int cordRepairs;

    private NeoplasmPerfMonitor(){
    }

    public static void init(){
        if(initialized) return;
        initialized = true;
        Events.run(Trigger.update, NeoplasmPerfMonitor::update);
    }

    /** Returns zero while disabled so normal gameplay pays no nanoTime cost. */
    public static long startTimer(){
        return enabled ? System.nanoTime() : 0L;
    }

    public static void endTimer(int timer, long startedAt){
        if(!enabled || startedAt == 0L || timer < 0 || timer >= timerNanos.length) return;
        long elapsed = System.nanoTime() - startedAt;
        if(elapsed < 0L) return;
        timerNanos[timer] += elapsed;
        timerCalls[timer]++;
    }

    public static void pathRequest(){ if(enabled) pathRequests++; }
    public static void riskDecision(){ if(enabled) riskDecisions++; }
    public static void turretScan(){ if(enabled) turretScans++; }
    public static void trafficScan(){ if(enabled) trafficScans++; }
    public static void cordScan(){ if(enabled) cordScans++; }
    public static void cordRepair(){ if(enabled) cordRepairs++; }

    private static String timeMs(int timer){
        long tenths = timerNanos[timer] / 100000L;
        return (tenths / 10L) + "." + (tenths % 10L);
    }

    private static void update(){
        if(Time.time >= nextSettingCheck){
            enabled = Core.settings != null && Core.settings.getBool("neoplasm-profiler");
            nextSettingCheck = Time.time + 60f;
            if(!enabled){
                reset();
                return;
            }
            if(nextReport <= 0f) nextReport = Time.time + 600f;
        }
        if(!enabled || Time.time < nextReport) return;
        nextReport = Time.time + 600f;

        int totalUnits = 0, neoplasmUnits = 0;
        int blob = 0, pallium = 0, mule = 0, worm = 0;
        int squid = 0, walky = 0, bomber = 0, kalyx = 0, matte = 0, otherNeoplasm = 0;

        for(Unit unit : Groups.unit){
            if(unit == null || unit.type == null) continue;
            totalUnits++;

            // UnitType identity is stable even when custom controllers are wrapped
            // or their display names differ from the localization/internal name.
            if(unit.type == RUnitTypes.blob) blob++;
            else if(unit.type == RUnitTypes.pallium) pallium++;
            else if(unit.type == RUnitTypes.mule) mule++;
            else if(unit.type == RUnitTypes.worm) worm++;
            else if(unit.type == RUnitTypes.squid) squid++;
            else if(unit.type == RUnitTypes.miserable) walky++;
            else if(unit.type == RUnitTypes.hydroBomber) bomber++;
            else if(unit.type == RUnitTypes.kalyx) kalyx++;
            else if(unit.type == RUnitTypes.matte) matte++;
            else{
                Object controller = unit.controller();
                boolean neoplasmController = controller instanceof NeoplasmAIController
                    || controller instanceof NeoplasmFlyingAI
                    || controller instanceof NeoplasmGroundAI
                    || controller instanceof NeoplasmHugAI
                    || controller instanceof SteamHugAI
                    || controller instanceof SuicideBomberAI
                    || controller instanceof HelperBlobAI;
                if(!neoplasmController) continue;
                otherNeoplasm++;
            }
            neoplasmUnits++;
        }

        Runtime runtime = Runtime.getRuntime();
        int usedMb = (int)((runtime.totalMemory() - runtime.freeMemory()) / 1048576L);
        int maxMb = (int)(runtime.maxMemory() / 1048576L);
        int fps = Core.graphics == null ? -1 : Core.graphics.getFramesPerSecond();

        Log.info("[Restoration Perf] fps=" + fps
            + " heap=" + usedMb + "/" + maxMb + "MB"
            + " units=" + totalUnits + " neoplasm=" + neoplasmUnits
            + " [blob=" + blob + ", pallium=" + pallium + ", mule=" + mule
            + ", worm=" + worm + ", squid=" + squid + ", walky=" + walky
            + ", bomber=" + bomber + ", kalyx=" + kalyx + ", matte=" + matte
            + ", other=" + otherNeoplasm + "]"
            + " /10s [path=" + pathRequests + ", risk=" + riskDecisions
            + ", turretScan=" + turretScans
            + ", trafficScan=" + trafficScans + ", cordScan=" + cordScans
            + ", cordRepair=" + cordRepairs + "]"
            + " ms[path=" + timeMs(TIMER_PATH) + "/" + timerCalls[TIMER_PATH]
            + ", steer=" + timeMs(TIMER_STEERING) + "/" + timerCalls[TIMER_STEERING]
            + ", turret=" + timeMs(TIMER_TURRET) + "/" + timerCalls[TIMER_TURRET]
            + ", traffic=" + timeMs(TIMER_TRAFFIC) + "/" + timerCalls[TIMER_TRAFFIC]
            + ", cord=" + timeMs(TIMER_CORD) + "/" + timerCalls[TIMER_CORD]
            + ", cordScan=" + timeMs(TIMER_CORD_SCAN) + "/" + timerCalls[TIMER_CORD_SCAN]
            + ", repair=" + timeMs(TIMER_REPAIR) + "/" + timerCalls[TIMER_REPAIR] + "]");

        reset();
    }

    private static void reset(){
        pathRequests = 0;
        riskDecisions = 0;
        turretScans = 0;
        trafficScans = 0;
        cordScans = 0;
        cordRepairs = 0;
        for(int i = 0; i < timerNanos.length; i++){
            timerNanos[i] = 0L;
            timerCalls[i] = 0;
        }
    }
}
