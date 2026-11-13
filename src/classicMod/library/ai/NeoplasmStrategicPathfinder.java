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
import java.util.HashMap;

/**
 * Team-wide, destination-specific route field that combines terrain cost,
 * remembered Neoplasm deaths, and enemy turret coverage.
 *
 * Fields are cached and rebuilt at most once every few seconds per team and
 * destination, so many units can share the same learned route.
 */
public final class NeoplasmStrategicPathfinder {
    // Full route maps are expensive on mobile. Rebuild at a fixed interval,
    // not once for every unit death recorded by the heat map.
    private static final long refreshMillis = 10000L;
    private static final float deathRiskWeight = 0.48f;
    private static final float turretRiskWeight = 1f;
    private static final float turretPower = 24f;
    private static final int maxTurretRadius = 128;

    private static final HashMap<Long, RouteField> fields = new HashMap<>();
    private static final HashMap<Integer, ThreatField> threatFields = new HashMap<>();
    private static Object threatWorld;
    private static int threatWidth = -1, threatHeight = -1;

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
            || current == destination || destination.array() < 0) return null;

        // Flying units often select different buildings as targets. A full
        // Dijkstra build for every target creates thousands of large route fields.
        // Use the shared threat map and score only the eight immediate moves.
        return chooseLocalSafeStep(current, destination, team);
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
            && cached.targetHash == targetHash
            && now - cached.builtAt < refreshMillis){
            return cached;
        }

        RouteField rebuilt = build(team, base, explicitTargets, targetHash, revision, now);
        if(rebuilt != null){
            // Route keys are few for normal core movement, but cap stale
            // destinations so a long match cannot retain unbounded map arrays.
            if(fields.size() >= 12 && !fields.containsKey(key)) fields.clear();
            fields.put(key, rebuilt);
        }
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
        NeoplasmDeathHeatmap.copyHeat(team, heat);
        float[] turret = getThreatField(team, width, height).risk;

        float[] distance = new float[length];
        Arrays.fill(distance, Float.POSITIVE_INFINITY);
        IndexHeap frontier = new IndexHeap(length, distance);

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
            int index = frontier.pop();
            float nodeCost = distance[index];

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
                float candidate = nodeCost + step;

                if(candidate + 0.001f < distance[next]){
                    distance[next] = candidate;
                    frontier.addOrDecrease(next);
                }
            }
        }

        return new RouteField(base, width, height, distance, revision, targetHash, now);
    }

    private static void addTarget(int tileArray, PathfinderCustom.Flowfield base,
                                  float[] distance, IndexHeap frontier){
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
            frontier.addOrDecrease(index);
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

    /** Shared turret exposure map; one full-world scan serves all flyers and route builds. */
    private static ThreatField getThreatField(Team team, int width, int height){
        long now = Time.millis();
        if(threatWorld != Vars.world || threatWidth != width || threatHeight != height){
            threatFields.clear();
            threatWorld = Vars.world;
            threatWidth = width;
            threatHeight = height;
        }

        int teamId = team.id;
        ThreatField cached = threatFields.get(teamId);
        if(cached != null && now - cached.builtAt < refreshMillis) return cached;

        float[] risk = new float[width * height];
        buildTurretRisk(team, risk, width, height);
        ThreatField rebuilt = new ThreatField(risk, now);
        threatFields.put(teamId, rebuilt);
        return rebuilt;
    }

    /** Cheap local steering for flyers: no per-building full-map Dijkstra. */
    private static Tile chooseLocalSafeStep(Tile current, Tile destination, Team team){
        int width = Vars.world.width(), height = Vars.world.height();
        if(current.x < 0 || current.y < 0 || current.x >= width || current.y >= height) return null;

        float[] turret = getThreatField(team, width, height).risk;
        int currentIndex = current.array();
        float currentHeat = NeoplasmDeathHeatmap.getHeat(team, current);
        float currentTurret = currentIndex >= 0 && currentIndex < turret.length ? turret[currentIndex] : 0f;
        float currentScore = (float)Math.sqrt(
            (double)(current.x - destination.x) * (current.x - destination.x)
            + (double)(current.y - destination.y) * (current.y - destination.y)
        ) + currentHeat * deathRiskWeight + currentTurret * turretRiskWeight;

        Tile best = null;
        float bestScore = currentScore;
        for(Point2 offset : Geometry.d8){
            int nx = current.x + offset.x, ny = current.y + offset.y;
            if(nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
            Tile next = Vars.world.tile(nx, ny);
            if(next == null || next == current) continue;

            int index = next.array();
            float heat = NeoplasmDeathHeatmap.getHeat(team, next);
            float risk = index >= 0 && index < turret.length ? turret[index] : 0f;
            float distance = (float)Math.sqrt(
                (double)(nx - destination.x) * (nx - destination.x)
                + (double)(ny - destination.y) * (ny - destination.y)
            );
            float score = distance + heat * deathRiskWeight + risk * turretRiskWeight;
            if(score + 0.05f < bestScore){
                bestScore = score;
                best = next;
            }
        }
        return best;
    }

    private static final class ThreatField{
        final float[] risk;
        final long builtAt;
        ThreatField(float[] risk, long builtAt){
            this.risk = risk;
            this.builtAt = builtAt;
        }
    }

    private static final class RouteField{
        final PathfinderCustom.Flowfield base;
        final int width, height;
        final float[] distance;
        final int heatRevision, targetHash;
        final long builtAt;

        RouteField(PathfinderCustom.Flowfield base, int width, int height, float[] distance,
                   int heatRevision, int targetHash, long builtAt){
            this.base = base;
            this.width = width;
            this.height = height;
            this.distance = distance;
            this.heatRevision = heatRevision;
            this.targetHash = targetHash;
            this.builtAt = builtAt;
        }
    }

    /**
     * Indexed binary min-heap. Each tile appears at most once, so the
     * full-map Dijkstra pass avoids allocating a Node object per relaxation.
     */
    private static final class IndexHeap{
        private final int[] heap;
        private final int[] positions;
        private final float[] priorities;
        private int size;

        IndexHeap(int capacity, float[] priorities){
            heap = new int[capacity];
            positions = new int[capacity];
            Arrays.fill(positions, -1);
            this.priorities = priorities;
        }

        boolean isEmpty(){
            return size == 0;
        }

        void addOrDecrease(int index){
            int position = positions[index];
            if(position == -2) return;
            if(position < 0){
                int at = size++;
                heap[at] = index;
                positions[index] = at;
                siftUp(at);
            }else{
                siftUp(position);
            }
        }

        int pop(){
            int result = heap[0];
            positions[result] = -2;
            int last = heap[--size];
            if(size > 0){
                heap[0] = last;
                positions[last] = 0;
                siftDown(0);
            }
            return result;
        }

        private void siftUp(int at){
            while(at > 0){
                int parent = (at - 1) >>> 1;
                if(priorities[heap[parent]] <= priorities[heap[at]]) break;
                swap(parent, at);
                at = parent;
            }
        }

        private void siftDown(int at){
            while(true){
                int left = at * 2 + 1;
                if(left >= size) return;
                int right = left + 1;
                int smallest = right < size && priorities[heap[right]] < priorities[heap[left]]
                    ? right : left;
                if(priorities[heap[at]] <= priorities[heap[smallest]]) return;
                swap(at, smallest);
                at = smallest;
            }
        }

        private void swap(int first, int second){
            int a = heap[first], b = heap[second];
            heap[first] = b;
            heap[second] = a;
            positions[a] = second;
            positions[b] = first;
        }
    }

    public static void clear(){
        fields.clear();
        threatFields.clear();
        threatWorld = null;
        threatWidth = -1;
        threatHeight = -1;
    }
}
