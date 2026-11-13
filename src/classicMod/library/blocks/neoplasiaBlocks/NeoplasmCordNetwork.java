package classicMod.library.blocks.neoplasiaBlocks;

import arc.Events;
import arc.struct.Seq;
import arc.util.Time;
import classicMod.content.RBlocks;
import classicMod.library.ai.NeoplasmPerfMonitor;
import mindustry.game.EventType.Trigger;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Prop;
import mindustry.world.blocks.environment.SteamVent;
import mindustry.content.Blocks;

import java.util.ArrayDeque;
import java.util.HashSet;

/**
 * Tracks which Neoplasm buildings are physically connected to a Heart and
 * lets disconnected cord segments lay a short, safe route back to the network.
 *
 * The scan is deliberately periodic and the repair search is bounded to keep
 * the cost predictable on Android.
 */
public final class NeoplasmCordNetwork {
    private static final float scanInterval = 300f;
    private static final int teamSlots = 256;

    private static boolean[][] connectedTiles = new boolean[teamSlots][];
    private static Object worldRef;
    private static int worldWidth = -1;
    private static int worldHeight = -1;
    private static float nextScan = -1f;
    private static float nextRepair = -1f;
    private static float lastManagerTick = Float.NEGATIVE_INFINITY;
    private static final float repairInterval = 12f;
    private static final int maxPendingRepairs = 512;
    private static final ArrayDeque<RepairRequest> pendingRepairs = new ArrayDeque<>();
    private static final HashSet<Long> repairKeys = new HashSet<>();
    private static final Seq<Building> scanQueue = new Seq<>();
    private static final int maxRepairDiameter = 65;
    private static final int maxRepairCells = maxRepairDiameter * maxRepairDiameter;
    private static final int[] repairParent = new int[maxRepairCells];
    private static final int[] repairQueue = new int[maxRepairCells];
    private static final int[] repairPath = new int[maxRepairCells];

    private NeoplasmCordNetwork(){
    }

    private static boolean initialized;

    /** Register global maintenance once; do not invoke it from every building update. */
    public static void init(){
        if(initialized) return;
        initialized = true;
        Events.run(Trigger.update, NeoplasmCordNetwork::update);
    }

    /** Refresh the Heart-connected components at most once every two seconds. */
    public static void update(){
        long perfStart = NeoplasmPerfMonitor.startTimer();
        try{
            updateInternal();
        }finally{
            NeoplasmPerfMonitor.endTimer(NeoplasmPerfMonitor.TIMER_CORD, perfStart);
        }
    }

    private static void updateInternal(){
        if(Vars.world == null || Vars.net.client()) return;

        int width = Vars.world.width();
        int height = Vars.world.height();
        if(worldRef != Vars.world || width != worldWidth || height != worldHeight){
            clear();
            worldRef = Vars.world;
            worldWidth = width;
            worldHeight = height;
        }

        // NeoplasmBlock.update() is called for every Neoplasm building. Run
        // global network work only once for this simulation tick.
        if(lastManagerTick == Time.time) return;
        lastManagerTick = Time.time;

        processRepairs();

        if(nextScan >= 0f && Time.time < nextScan) return;
        nextScan = Time.time + scanInterval;
        long scanStart = NeoplasmPerfMonitor.startTimer();
        NeoplasmPerfMonitor.cordScan();

        // Reuse the per-team map instead of allocating a world-sized boolean
        // array for every Heart on every scan.
        for(boolean[] teamTiles : connectedTiles){
            if(teamTiles != null) java.util.Arrays.fill(teamTiles, false);
        }
        Seq<Building> queue = scanQueue;

        // Each live Heart is a root. Its friendly adjacent Neoplasm buildings
        // are traversed through Mindustry's existing proximity links.
        for(Tile tile : Vars.world.tiles){
            if(!(tile.build instanceof CausticHeart.HeartBuilding heart)) continue;
            if(!heart.isValid() || heart.team == null || heart.team.id < 0 || heart.team.id >= teamSlots) continue;

            int teamId = heart.team.id;
            if(connectedTiles[teamId] == null || connectedTiles[teamId].length != width * height){
                connectedTiles[teamId] = new boolean[width * height];
            }

            queue.clear();
            int rootIndex = heart.tile == null ? -1 : heart.tile.array();
            if(rootIndex < 0 || rootIndex >= connectedTiles[teamId].length || connectedTiles[teamId][rootIndex]) continue;
            connectedTiles[teamId][rootIndex] = true;
            queue.add(heart);
            int cursor = 0;

            while(cursor < queue.size){
                Building current = queue.get(cursor++);
                if(!(current instanceof NeoplasmBlock.NeoplasmBuilding)
                    || !current.isValid() || current.team != heart.team || current.tile == null) continue;

                for(Building neighbor : current.proximity){
                    if(neighbor instanceof NeoplasmBlock.NeoplasmBuilding
                        && neighbor.team == heart.team && neighbor.isValid() && neighbor.tile != null){
                        int neighborIndex = neighbor.tile.array();
                        if(neighborIndex >= 0 && neighborIndex < connectedTiles[teamId].length
                            && !connectedTiles[teamId][neighborIndex]){
                            // Mark on enqueue, preventing duplicate frontier entries.
                            connectedTiles[teamId][neighborIndex] = true;
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
        NeoplasmPerfMonitor.endTimer(NeoplasmPerfMonitor.TIMER_CORD_SCAN, scanStart);

    }

    /** Queue a repair request; actual path searches are globally throttled. */
    public static void requestReconnect(Tile start, Team team, int maxRadius){
        if(Vars.world == null || Vars.net.client() || start == null || team == null) return;
        if(isConnected(team, start) || pendingRepairs.size() >= maxPendingRepairs) return;
        long key = ((long)team.id << 32) ^ (start.array() & 0xffffffffL);
        if(repairKeys.add(key)){
            pendingRepairs.addLast(new RepairRequest(start, team, maxRadius, key));
        }
    }

    private static void processRepairs(){
        if(pendingRepairs.isEmpty() || (nextRepair >= 0f && Time.time < nextRepair)) return;
        nextRepair = Time.time + repairInterval;

        RepairRequest request = pendingRepairs.pollFirst();
        if(request == null) return;
        repairKeys.remove(request.key);

        if(request.start == null || request.team == null || isConnected(request.team, request.start)) return;
        NeoplasmPerfMonitor.cordRepair();
        long repairStart = NeoplasmPerfMonitor.startTimer();
        try{
            tryReconnect(request.start, request.team, request.maxRadius);
        }finally{
            NeoplasmPerfMonitor.endTimer(NeoplasmPerfMonitor.TIMER_REPAIR, repairStart);
        }
    }

    private static final class RepairRequest{
        final Tile start;
        final Team team;
        final int maxRadius;
        final long key;
        RepairRequest(Tile start, Team team, int maxRadius, long key){
            this.start = start;
            this.team = team;
            this.maxRadius = maxRadius;
            this.key = key;
        }
    }

    /** Whether a tile belongs to a component physically connected to a Heart. */
    public static boolean isConnected(Team team, Tile tile){
        if(team == null || tile == null || team.id < 0 || team.id >= teamSlots) return false;
        boolean[] teamTiles = connectedTiles[team.id];
        if(teamTiles == null) return false;
        int index = tile.array();
        return index >= 0 && index < teamTiles.length && teamTiles[index];
    }

    /**
     * Finds a short four-direction route from a disconnected cord to any
     * Heart-connected tile. Only empty terrain, props, vents and friendly
     * Neoplasm buildings may be crossed. The first missing cord tile is built.
     */
    public static boolean tryReconnect(Tile start, Team team, int maxRadius){
        if(Vars.world == null || Vars.net.client() || start == null || team == null) return false;
        if(isConnected(team, start)) return false;

        int radius = Math.max(4, Math.min(maxRadius, 32));
        int diameter = radius * 2 + 1;
        int cellCount = diameter * diameter;
        int[] parent = repairParent;
        int[] queue = repairQueue;
        int[] reversePath = repairPath;
        java.util.Arrays.fill(parent, 0, cellCount, -1);

        int origin = radius * diameter + radius;
        parent[origin] = origin;
        int head = 0, tail = 0;
        queue[tail++] = origin;
        int goal = -1;

        while(head < tail){
            int currentIndex = queue[head++];
            int localX = currentIndex % diameter;
            int localY = currentIndex / diameter;
            int worldX = start.x + localX - radius;
            int worldY = start.y + localY - radius;
            Tile current = Vars.world.tile(worldX, worldY);
            if(current == null) continue;

            if(currentIndex != origin && isConnected(team, current)){
                goal = currentIndex;
                break;
            }

            for(int direction = 0; direction < 4; direction++){
                int nextX = localX + (direction == 0 ? 1 : direction == 2 ? -1 : 0);
                int nextY = localY + (direction == 1 ? 1 : direction == 3 ? -1 : 0);
                if(nextX < 0 || nextY < 0 || nextX >= diameter || nextY >= diameter) continue;

                int nextIndex = nextX + nextY * diameter;
                if(parent[nextIndex] != -1) continue;

                Tile next = Vars.world.tile(start.x + nextX - radius, start.y + nextY - radius);
                if(next == null || !canTraverse(next, team)) continue;

                parent[nextIndex] = currentIndex;
                queue[tail++] = nextIndex;
            }
        }

        if(goal < 0) return false;

        // Reconstruct from the connected target back to the starting cord.
        int pathSize = 0;
        int cursor = goal;
        while(cursor != origin && cursor >= 0 && pathSize < reversePath.length){
            reversePath[pathSize++] = cursor;
            cursor = parent[cursor];
        }
        if(cursor != origin || pathSize == 0) return false;

        // Walk from the cord outward and fill the first empty tile on the path.
        for(int i = pathSize - 1; i >= 0; i--){
            int pathIndex = reversePath[i];
            int localX = pathIndex % diameter;
            int localY = pathIndex / diameter;
            Tile step = Vars.world.tile(start.x + localX - radius, start.y + localY - radius);
            if(step == null || step.build != null || !isPlaceable(step)) continue;

            Tile previous;
            if(i == pathSize - 1){
                previous = start;
            }else{
                int previousIndex = reversePath[i + 1];
                previous = Vars.world.tile(
                    start.x + previousIndex % diameter - radius,
                    start.y + previousIndex / diameter - radius
                );
            }

            step.setBlock(RBlocks.cord, team);
            if(step.build instanceof CausticCord.CordBuild cord){
                cord.facingRot = previous.relativeTo(step);
            }
            return true;
        }

        return false;
    }

    private static boolean canTraverse(Tile tile, Team team){
        if(tile == null || tile.floor() == null || tile.floor().isLiquid || tile.floor().liquidDrop != null) return false;
        if(isConnected(team, tile)) return true;

        Block block = tile.block();
        if(block == Blocks.air || block instanceof Prop || block instanceof SteamVent) return true;
        return block instanceof NeoplasmBlock && tile.build != null && tile.build.team == team;
    }

    private static boolean isPlaceable(Tile tile){
        if(tile == null || tile.floor() == null || tile.floor().isLiquid || tile.floor().liquidDrop != null) return false;
        Block block = tile.block();
        return block == Blocks.air || block instanceof Prop || block instanceof SteamVent;
    }

    /** Reset all cached component data after a world change. */
    public static void clear(){
        connectedTiles = new boolean[teamSlots][];
        worldRef = null;
        worldWidth = -1;
        worldHeight = -1;
        nextScan = -1f;
        nextRepair = -1f;
        lastManagerTick = Float.NEGATIVE_INFINITY;
        pendingRepairs.clear();
        repairKeys.clear();
    }
}
