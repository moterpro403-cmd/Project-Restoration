package classicMod.library.ai;

import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.util.Time;
import classicMod.content.RVars;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.Turret;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;

/**
 * Team-wide, destination-specific route field that combines terrain cost,
 * remembered Neoplasm deaths, and enemy turret coverage.
 *
 * Fields are cached and rebuilt at most once every few seconds per team and
 * destination, so many units can share the same learned route.
 */
public final class NeoplasmStrategicPathfinder {
    private static final long refreshMillis = 4500L;
    private static final float deathRiskWeight = 0.48f;
    private static final float turretRiskWeight = 1f;
    private static final float turretPower = 24f;
    private static final int maxTurretRadius = 128;

    private static final HashMap<Long, RouteField> fields = new HashMap<>();

    private NeoplasmStrategicPathfinder(){
    }

    /** Route to an existing shared flowfield target set, such as the enemy core. */
    public static Tile nextStep(Tile current, Team team, int costType, int pathTarget){
        if(current == null || team == null || Vars.world == null || RVars.pathfinderCustom == null) return null;
        PathfinderCustom.Flowfield base = RVars.pathfinderCustom.getField(team, costType, pathTarget);
        if(base == null || !base.initialized || !base.hasTargets() || base.resolution != 1) return null;

        long key = key(team, costType, pathTarget, false, false);
        RouteField route = getOrBuild(key, team, base, null, false);
        return chooseNext(current, base, route);
    }

    /** Route to a specific building, used by direct-movement flying AI. */
    public static Tile nextStepTo(Tile current, Tile destination, Team team, int costType){
        if(current == null || destination == null || team == null || Vars.world == null
            || RVars.pathfinderCustom == null || current == destination || destination.array() < 0) return null;

        PathfinderCustom.Flowfield base = RVars.pathfinderCustom.getField(team, costType, PathfinderCustom.fieldCore);
        if(base == null || !base.initialized || base.resolution != 1) return null;

        long key = key(team, costType, destination.array(), true, false);
        RouteField route = getOrBuild(key, team, base, new int[]{destination.array()}, true);
        return chooseNext(current, base, route);
    }

    private static long key(Team team, int costType, int target, boolean position, boolean air){
        return ((long)team.id << 56)
            | ((long)(costType & 0x0f) << 52)
            | ((long)(position ? 1 : 0) << 51)
            | ((long)(air ? 1 : 0) << 50)
            | (target & 0x3fffffffffffffL);
    }

    private static RouteField getOrBuild(long key, Team team, PathfinderCustom.Flowfield base,
                                         int[] explicitTargets, boolean positionTarget){
        long now = Time.millis();
        int revision = NeoplasmDeathHeatmap.getRevision(team);
        int targetHash = targetHash(base, explicitTargets);

        RouteField cached = fields.get(key);
        if(cached != null && cached.base == base
            && cached.width == base.width && cached.height == base.height
            && cached.heatRevision == revision
            && cached.targetHash == targetHash
            && now - cached.builtAt < refreshMillis){
            return cached;
        }

        RouteField rebuilt = build(team, base, explicitTargets, targetHash, revision, now);
        if(rebuilt != null) fields.put(key, rebuilt);
        return rebuilt;
    }

    private static int targetHash(PathfinderCustom.Flowfield base, int[] explicitTargets){
        int hash = 1;
        if(explicitTargets != null){
            for(int target : explicitTargets) hash = 31 * hash + target;
        }else{
            synchronized(base.targets){
                for(int i = 0; i < base.targets.size; i++){
                    hash = 31 * hash + base.targets.get(i);
                }
            }
        }
        return hash;
    }

    private static RouteField build(Team team, PathfinderCustom.Flowfield base,
                                    int[] explicitTargets, int targetHash, int revision, long now){
        int width = base.width, height = base.height;
        int length = width * height;
        if(length <= 0 || RVars.pathfinderCustom.tiles == null
            || RVars.pathfinderCustom.tiles.length < Vars.world.width() * Vars.world.height()) return null;

        float[] heat = new float[length];
        float[] turret = new float[length];
        NeoplasmDeathHeatmap.copyHeat(team, heat);
        buildTurretRisk(team, turret, width, height);

        float[] distance = new float[length];
        Arrays.fill(distance, Float.POSITIVE_INFINITY);
        PriorityQueue<Node> frontier = new PriorityQueue<>(11, new Comparator<Node>() {
            @Override
            public int compare(Node first, Node second){
                if(first.cost < second.cost) return -1;
                if(first.cost > second.cost) return 1;
                return 0;
            }
        });

        if(explicitTargets != null){
            for(int target : explicitTargets) addTarget(target, base, distance, frontier);
        }else{
            synchronized(base.targets){
                for(int i = 0; i < base.targets.size; i++){
                    addTarget(base.targets.get(i), base, distance, frontier);
                }
            }
        }
        if(frontier.isEmpty()) return null;

        final int[] dx = {0, 1, 0, -1, 1, 1, -1, -1};
        final int[] dy = {1, 0, -1, 0, 1, -1, -1, 1};

        while(!frontier.isEmpty()){
            Node node = frontier.poll();
            int index = node.index;
            if(node.cost != distance[index]) continue;

            int x = index % width;
            int y = index / width;

            for(int d = 0; d < 8; d++){
                int nx = x + dx[d], ny = y + dy[d];
                if(nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                int next = nx + ny * width;
                if(!base.passable(next)) continue;

                if(dx[d] != 0 && dy[d] != 0){
                    int horizontal = (x + dx[d]) + y * width;
                    int vertical = x + (y + dy[d]) * width;
                    if(!base.passable(horizontal) || !base.passable(vertical)) continue;
                }

                Tile tile = Vars.world.tile(nx, ny);
                if(tile == null) continue;
                int tileIndex = tile.array();
                float terrain = base.getCost(RVars.pathfinderCustom.tiles, tileIndex);
                if(terrain < 1f) terrain = 1f;

                float risk = heat[tileIndex] * deathRiskWeight
                    + turret[tileIndex] * turretRiskWeight;
                float step = (terrain + risk) * (dx[d] != 0 && dy[d] != 0 ? 1.41421356f : 1f);
                float candidate = node.cost + step;

                if(candidate + 0.001f < distance[next]){
                    distance[next] = candidate;
                    frontier.add(new Node(next, candidate));
                }
            }
        }

        return new RouteField(base, width, height, distance, heat, turret, revision, targetHash, now);
    }

    private static void addTarget(int tileArray, PathfinderCustom.Flowfield base,
                                  float[] distance, PriorityQueue<Node> frontier){
        if(tileArray < 0 || tileArray >= Vars.world.width() * Vars.world.height()) return;
        Tile tile = Vars.world.tile(tileArray % Vars.world.width(), tileArray / Vars.world.width());
        if(tile == null) return;
        int x = tile.x / base.resolution;
        int y = tile.y / base.resolution;
        if(x < 0 || y < 0 || x >= base.width || y >= base.height) return;
        int index = x + y * base.width;
        if(!base.passable(index)) return;
        if(distance[index] > 0f){
            distance[index] = 0f;
            frontier.add(new Node(index, 0f));
        }
    }

    private static void buildTurretRisk(Team team, float[] risk, int width, int height){
        for(Tile turretTile : Vars.world.tiles){
            if(!(turretTile.build instanceof Turret.TurretBuild turretBuild)
                || !turretBuild.isValid() || turretBuild.team == team) continue;

            float range = Math.max(0f, turretBuild.range());
            int radius = Math.min(maxTurretRadius, (int)Math.ceil(range / Vars.tilesize));
            if(radius <= 0) continue;

            for(int dy = -radius; dy <= radius; dy++){
                int y = turretTile.y + dy;
                if(y < 0 || y >= height) continue;
                for(int dx = -radius; dx <= radius; dx++){
                    int x = turretTile.x + dx;
                    if(x < 0 || x >= width) continue;

                    float distanceWorld = (float)Math.sqrt(dx * dx + dy * dy) * Vars.tilesize;
                    if(distanceWorld > range) continue;

                    float falloff = 1f - distanceWorld / Math.max(range, 1f);
                    int index = x + y * width;
                    risk[index] = Math.min(120f, risk[index] + turretPower * falloff * falloff);
                }
            }
        }
    }

    private static Tile chooseNext(Tile current, PathfinderCustom.Flowfield base, RouteField route){
        if(route == null || current == null) return null;
        int x = current.x, y = current.y;
        if(x < 0 || y < 0 || x >= route.width || y >= route.height) return null;
        int index = x + y * route.width;
        if(index < 0 || index >= route.distance.length || Float.isInfinite(route.distance[index])) return null;

        int normalIndex = -1;
        int[] normalWeights = base.hasComplete ? base.completeWeights : base.weights;
        float normalBest = Float.POSITIVE_INFINITY;

        int strategicIndex = -1;
        float strategicBest = Float.POSITIVE_INFINITY;

        for(Point2 offset : Geometry.d8){
            int nx = x + offset.x, ny = y + offset.y;
            if(nx < 0 || ny < 0 || nx >= route.width || ny >= route.height) continue;
            int next = nx + ny * route.width;
            if(!base.passable(next)) continue;

            if(offset.x != 0 && offset.y != 0){
                if(!base.passable((x + offset.x) + y * route.width)
                    || !base.passable(x + (y + offset.y) * route.width)) continue;
            }

            float routeCost = route.distance[next];
            if(routeCost < strategicBest){
                strategicBest = routeCost;
                strategicIndex = next;
            }

            if(normalWeights != null && next < normalWeights.length && normalWeights[next] >= 0
                && normalWeights[next] < normalBest){
                normalBest = normalWeights[next];
                normalIndex = next;
            }
        }

        if(strategicIndex < 0 || strategicIndex == index || strategicBest >= route.distance[index]) return null;
        if(normalIndex == strategicIndex) return null;

        // Avoid rerouting because of insignificant cost noise; the strategic
        // path must be meaningfully different from the original shortest field.
        float baseline = index < normalWeights.length && normalWeights[index] >= 0
            ? normalWeights[index] : Float.POSITIVE_INFINITY;
        if(!Float.isInfinite(baseline)
            && route.distance[index] - baseline < 1f) return null;

        return Vars.world.tile(strategicIndex % route.width, strategicIndex / route.width);
    }

    private static final class RouteField{
        final PathfinderCustom.Flowfield base;
        final int width, height;
        final float[] distance, heat, turret;
        final int heatRevision, targetHash;
        final long builtAt;

        RouteField(PathfinderCustom.Flowfield base, int width, int height, float[] distance,
                   float[] heat, float[] turret, int heatRevision, int targetHash, long builtAt){
            this.base = base;
            this.width = width;
            this.height = height;
            this.distance = distance;
            this.heat = heat;
            this.turret = turret;
            this.heatRevision = heatRevision;
            this.targetHash = targetHash;
            this.builtAt = builtAt;
        }
    }

    private static final class Node{
        final int index;
        final float cost;
        Node(int index, float cost){
            this.index = index;
            this.cost = cost;
        }
    }

    public static void clear(){
        fields.clear();
    }
}
