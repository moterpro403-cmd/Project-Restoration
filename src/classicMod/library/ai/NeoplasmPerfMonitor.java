package classicMod.library.ai;

import arc.Core;
import arc.Events;
import arc.util.Log;
import arc.util.Time;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

/**
 * Optional ten-second snapshot of Neoplasm workload and JVM heap usage.
 * Enable "Neoplasm performance profiler" in Project: Restoration settings.
 */
public final class NeoplasmPerfMonitor {
    private static boolean initialized;
    private static boolean enabled;
    private static float nextSettingCheck;
    private static float nextReport;

    private static int pathRequests;
    private static int riskDecisions;
    private static int routeBuilds;
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

    public static void pathRequest(){ if(enabled) pathRequests++; }
    public static void riskDecision(){ if(enabled) riskDecisions++; }
    public static void routeBuild(){ if(enabled) routeBuilds++; }
    public static void turretScan(){ if(enabled) turretScans++; }
    public static void trafficScan(){ if(enabled) trafficScans++; }
    public static void cordScan(){ if(enabled) cordScans++; }
    public static void cordRepair(){ if(enabled) cordRepairs++; }

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
        int squid = 0, walky = 0, bomber = 0, otherNeoplasm = 0;

        for(Unit unit : Groups.unit){
            if(unit == null || unit.type == null) continue;
            totalUnits++;
            String name = unit.type.name;
            boolean knownNeoplasm = name.equals("vesicle") || name.equals("pallium")
                || name.equals("mule") || name.equals("worm")
                || name.equals("flying-neoplasm-squid") || name.equals("walky")
                || name.equals("hydro-bomber") || name.equals("kalyx")
                || name.equals("matte");

            Object controller = unit.controller();
            boolean neoplasmController = controller instanceof NeoplasmAIController
                || controller instanceof NeoplasmFlyingAI
                || controller instanceof NeoplasmGroundAI
                || controller instanceof NeoplasmHugAI;
            if(!knownNeoplasm && !neoplasmController) continue;

            neoplasmUnits++;
            if(name.equals("vesicle")) blob++;
            else if(name.equals("pallium")) pallium++;
            else if(name.equals("mule")) mule++;
            else if(name.equals("worm")) worm++;
            else if(name.equals("flying-neoplasm-squid")) squid++;
            else if(name.equals("walky")) walky++;
            else if(name.equals("hydro-bomber")) bomber++;
            else otherNeoplasm++;
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
            + ", bomber=" + bomber + ", other=" + otherNeoplasm + "]"
            + " /10s [path=" + pathRequests + ", risk=" + riskDecisions
            + ", dijkstra=" + routeBuilds + ", turretScan=" + turretScans
            + ", trafficScan=" + trafficScans + ", cordScan=" + cordScans
            + ", cordRepair=" + cordRepairs + "]");

        reset();
    }

    private static void reset(){
        pathRequests = 0;
        riskDecisions = 0;
        routeBuilds = 0;
        turretScans = 0;
        trafficScans = 0;
        cordScans = 0;
        cordRepairs = 0;
    }
}
