package classicMod.library.blocks.neoplasiaBlocks;

import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.Items;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.Tile;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/**
 * Bounded breadth-first logistics for connected Neoplasm cord veins.
 * Moves one item per cord heartbeat and prefers valid consumers/growth fronts.
 */
public final class NeoplasmResourceRouter{
    private static final int maxNodes = 384;
    private static final int maxHops = 32;
    private static final int growthThreshold = 5;
    private static final float retryTicks = 12f;
    private static final ArrayDeque<CausticCord.CordBuild> queue = new ArrayDeque<>();
    private static final IdentityHashMap<CausticCord.CordBuild, CausticCord.CordBuild> firstHop = new IdentityHashMap<>();
    private static final IdentityHashMap<CausticCord.CordBuild, Integer> depth = new IdentityHashMap<>();
    private static final IdentityHashMap<CausticCord.CordBuild, Float> retry = new IdentityHashMap<>();
    private static boolean busy;

    private NeoplasmResourceRouter(){}

    public static void update(CausticCord.CordBuild source){
        if(source == null || busy || source.tile == null || source.team == null || source.items == null) return;
        Item item = source.items.first();
        if(item == null){ source.current = null; retry.remove(source); return; }
        source.current = item;
        Float until = retry.get(source);
        if(until != null && until > Time.time) return;

        busy = true;
        try{
            queue.clear();
            firstHop.clear();
            depth.clear();
            queue.add(source);
            firstHop.put(source, source);
            depth.put(source, 0);

            NeoplasmBlock.NeoplasmBuilding best = null;
            CausticCord.CordBuild step = null;
            int bestPriority = -1, bestDistance = Integer.MAX_VALUE;
            int visited = 0;

            while(!queue.isEmpty() && visited++ < maxNodes){
                CausticCord.CordBuild node = queue.removeFirst();
                Integer d = depth.get(node);
                if(d == null || d >= maxHops || node.proximity == null) continue;

                Seq<Building> neighbors = node.proximity;
                for(int i=0;i<neighbors.size;i++){
                    Building building = neighbors.get(i);
                    if(building == null || building.team != source.team || building == node) continue;
                    NeoplasmBlock.NeoplasmBuilding neo = node.getNeoplasm(building);
                    if(neo == null || neo == node || !node.validBuilding(neo, item)) continue;
                    int nextDepth = d + 1;

                    if(neo instanceof CausticCord.CordBuild cord){
                        int priority = -1;
                        if(cord.grow && cord.getTotal(item, 3) < growthThreshold) priority = 0;
                        if(neo.block instanceof CausticSpawner) priority = 2;
                        if(neo instanceof CausticHeart.HeartBuilding && item == Items.graphite) priority = 3;
                        if(priority > bestPriority || (priority == bestPriority && nextDepth < bestDistance)){
                            if(priority >= 0){
                                best = neo;
                                step = d == 0 ? cord : firstHop.get(node);
                                bestPriority = priority;
                                bestDistance = nextDepth;
                            }
                        }
                        if(!depth.containsKey(cord)){
                            depth.put(cord, nextDepth);
                            firstHop.put(cord, d == 0 ? cord : firstHop.get(node));
                            queue.addLast(cord);
                        }
                    }else{
                        int priority = neo.block instanceof CausticSpawner ? 2
                            : (neo instanceof CausticHeart.HeartBuilding && item == Items.graphite ? 3 : 1);
                        if(priority > bestPriority || (priority == bestPriority && nextDepth < bestDistance)){
                            best = neo;
                            step = d == 0 ? null : firstHop.get(node);
                            bestPriority = priority;
                            bestDistance = nextDepth;
                        }
                    }
                }
            }

            Building recipient = step != null ? step : best;
            if(recipient == null || recipient == source || !source.validBuilding(recipient, item)){
                retry.put(source, Time.time + retryTicks);
                return;
            }
            source.items.remove(item, 1);
            recipient.handleItem(source, item);
            source.current = source.items.first();
            retry.remove(source);
        }finally{
            busy = false;
        }
    }

    /** Use the registered ore flowfield rather than a straight-line nearest scan. */
    public static Tile nextReachableOre(CausticCord.CordBuild cord){
        return cord == null ? null : cord.pathfind(classicMod.library.ai.PathfinderExtended.fieldOres);
    }

    public static void clear(){
        queue.clear(); firstHop.clear(); depth.clear(); retry.clear(); busy = false;
    }
}
