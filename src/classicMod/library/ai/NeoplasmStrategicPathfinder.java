package classicMod.library.ai;

import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.util.Time;
import classicMod.content.RVars;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
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
    // A changed death field can refresh sooner than the normal cache timeout,
    // but throttle rebuilds so a burst of deaths cannot trigger one per event.
    private static final long heatRevisionRefreshMillis = 1500L;
    private static final long trafficRefreshMillis = 1000L;
    private static final float congestionWeight = 5f;
    private static final float deathRiskWeight = 0.48f;
    private static final float turretRiskWeight = 1f;
    private static final float turretPower = 24f;
    private static final int maxTurretRadius = 128;

    private static final HashMap<Long, RouteField> fields = new HashMap<>();
    private static final HashMap<Integer, ThreatField> threatFields = new HashMap<>();
    private static final HashMap<Integer, TrafficField> trafficFields = new HashMap<>();
    private static Object threatWorld;
    private static int threatWidth = -1, threatHeight = -1;

    private NeoplasmStrategicPathfinder(){
    }

    /** Route to an existing shared flowfield target set, such as the enemy core. */
    public static Tile nextStep(Tile current, Team team, int costType, int pathTarget, int unitId){
        if(current == null || team == null || Vars.world == null || RVars.pathfinderCustom == null) return null;
        PathfinderCustom.Flowfield base = RVars.pathfinderCustom.getField(team, costType, pathTarget);
        if(base == null || !base.initialized || !base.hasTargets() || base.resolution != 1) return null;

        // Do not build an expensive full-map Dijkstra field for units moving
        // through safe terrain. Build/share one only when a turret or remembered
        // death is affecting the current tile or one of its immediate exits.
        if(!hasLocalThreat(current, team, Vars.world.width(), Vars.world.height())) return null;

        long key = key(team, costType, pathTarget, false, false);
        RouteField route = getOrBuild(key, team, base, null, false);
        return chooseNext(current, team, base, route, unitId);
    }

    private static final float localHeatThreshold = 2f;
    private static final float localTurretThreshold = 1f;

    private static boolean hasLocalThreat(Tile current, Team team, int width, int height){
        if(current == null || team == null || Vars.world == null) return false;
        float[] turret = getThreatField(team, width, height).risk;

        for(int dy = -1; dy <= 1; dy++){
            for(int dx = -1; dx <= 1; dx++){
                Tile tile = Vars.world.tile(current.x + dx, current.y + dy);
                if(tile == null) continue;
                int index = tile.array();
                if(index < 0 || index >= turret.length) continue;
                if(turret[index] >= localTurretThreshold
                    || NeoplasmDeathHeatmap.getHeat(team, tile) >= localHeatThreshold){
                    return true;
                }
            }
        }
        return false;
    }

    /** Route to a specific building, used by direct-movement flying AI. */
    public static Tile nextStepTo(Tile current, Tile destination, Team team, int costType, int unitId){
        if(current == null || destination == null || team == null || Vars.world == null
            || current == destination || destination.array() < 0) return null;

        // Flying units often select different buildings as targets. A full
        // Dijkstra build for every target creates thousands of large route fields.
        // Use the shared threat map and score only the eight immediate moves.
        return chooseLocalSafeStep(current, destination, team, unitId);
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
        boolean heatChanged = cached != null && cached.heatRevision != revision;
        boolean heatRefreshThrottled = heatChanged && cached != null
            && now - cached.builtAt < heatRevisionRefreshMillis;
        if(cached != null && cached.base == base
            && cached.width == base.width && cached.height == base.height
            && cached.targetHash == targetHash
            && now - cached.builtAt < refreshMillis
            && (!heatChanged || heatRefreshThrottled)){
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
        long perfStart = NeoplasmPerfMonitor.startTimer();
        try{
            return buildInternal(team, base, explicitTargets, targetHash, revision, now);
        }finally{
            NeoplasmPerfMonitor.endTimer(NeoplasmPerfMonitor.TIMER_ROUTE, perfStart);
        }
    }

    private static RouteField buildInternal(Team team, PathfinderCustom.Flowfield base,
                                            int[] explicitTargets, int targetHash, int revision, long now){
        NeoplasmPerfMonitor.routeBuild();
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
        long perfStart = NeoplasmPerfMonitor.startTimer();
        try{
            buildTurretRiskInternal(team, risk, width, height);
        }finally{
            NeoplasmPerfMonitor.endTimer(NeoplasmPerfMonitor.TIMER_TURRET, perfStart);
        }
    }

    private static void buildTurretRiskInternal(Team team, float[] risk, int width, int height){
        NeoplasmPerfMonitor.turretScan();
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

    private static Tile chooseNext(Tile current, Team team, PathfinderCustom.Flowfield base,
                                   RouteField route, int unitId){
        if(route == null || current == null) return null;
        int x = current.x, y = current.y;
        if(x < 0 || y < 0 || x >= route.width || y >= route.height) return null;
        int index = x + y * route.width;
        if(index < 0 || index >= route.distance.length || Float.isInfinite(route.distance[index])) return null;

        float[] traffic = getTrafficField(team, route.width, route.height).density;
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
            // Only choose steps that still make forward progress. Congestion
            // breaks ties between viable steps instead of causing random loops.
            if(routeCost >= route.distance[index]) continue;

            float crowd = traffic[next];
            // Stable per-unit tie-break helps nearby units fan out when crowd
            // costs are equal, without making their route oscillate every tick.
            float tie = ((unitId * 31 + next * 17) & 7) * 0.015625f;
            float score = routeCost + crowd * congestionWeight + tie;
            if(score < strategicBest){
                strategicBest = score;
                strategicIndex = next;
            }
        }

        if(strategicIndex < 0 || strategicIndex == index) return null;
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
    private static Tile chooseLocalSafeStep(Tile current, Tile destination, Team team, int unitId){
        int width = Vars.world.width(), height = Vars.world.height();
        if(current.x < 0 || current.y < 0 || current.x >= width || current.y >= height) return null;

        float[] turret = getThreatField(team, width, height).risk;
        float[] traffic = getTrafficField(team, width, height).density;
        int currentIndex = current.array();
        float currentHeat = NeoplasmDeathHeatmap.getHeat(team, current);
        float currentTurret = currentIndex >= 0 && currentIndex < turret.length ? turret[currentIndex] : 0f;
        float currentScore = (float)Math.sqrt(
            (double)(current.x - destination.x) * (current.x - destination.x)
            + (double)(current.y - destination.y) * (current.y - destination.y)
        ) + currentHeat * deathRiskWeight + currentTurret * turretRiskWeight
            + traffic[currentIndex] * congestionWeight;

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
            float tie = ((unitId * 31 + index * 17) & 7) * 0.015625f;
            float score = distance + heat * deathRiskWeight + risk * turretRiskWeight
                + traffic[index] * congestionWeight + tie;
            if(score + 0.05f < bestScore){
                bestScore = score;
                best = next;
            }
        }
        return best;
    }

    /**
     * Periodically samples allied unit positions. This is intentionally a
     * compact per-team density map, not a per-unit scan during each decision.
     */
    private static TrafficField getTrafficField(Team team, int width, int height){
        long now = Time.millis();
        TrafficField cached = trafficFields.get(team.id);
        if(cached != null && cached.width == width && cached.height == height
            && now - cached.builtAt < trafficRefreshMillis){
            return cached;
        }

        NeoplasmPerfMonitor.trafficScan();
        long perfStart = NeoplasmPerfMonitor.startTimer();
        try{
        float[] density = new float[width * height];
        Groups.unit.each(other -> {
            if(other == null || other.team != team || other.dead() || !other.isAdded()) return;
            Tile tile = other.tileOn();
            if(tile == null) return;

            int index = tile.array();
            if(index < 0 || index >= density.length) return;
            density[index] += 1f;
        });

        TrafficField rebuilt = new TrafficField(density, width, height, now);
        trafficFields.put(team.id, rebuilt);
        return rebuilt;
        }finally{
            NeoplasmPerfMonitor.endTimer(NeoplasmPerfMonitor.TIMER_TRAFFIC, perfStart);
        }
    }

    private static final class TrafficField{
        final float[] density;
        final int width, height;
        final long builtAt;
        TrafficField(float[] density, int width, int height, long builtAt){
            this.density = density;
            this.width = width;
            this.height = height;
            this.builtAt = builtAt;
        }
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
        trafficFields.clear();
        threatWorld = null;
        threatWidth = -1;
        threatHeight = -1;
    }
}
