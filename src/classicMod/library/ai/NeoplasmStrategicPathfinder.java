package classicMod.library.ai;

import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.util.Time;
import classicMod.content.RVars;
import mindustry.Vars;
import mindustry.ai.Pathfinder;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.BaseShield;
import mindustry.world.blocks.defense.ForceProjector;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.blocks.defense.turrets.Turret;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Team-wide, destination-specific route field that combines:
 *  1. Terrain traversal cost
 *  2. Terrain openness / chokepoint narrowness (two-pass distance transform)
 *  3. Enemy turret coverage, overlapping crossfire, shields, and wall lines
 *  4. Remembered Neoplasm unit deaths
 *
 * Used by BOTH ground/naval flowfield units AND flying/direct-target units so
 * every non-suicide Neoplasm unit detects fortified chokepoints early and
 * willingly takes longer, open flanking routes around heavy defenses.
 */
public final class NeoplasmStrategicPathfinder {
    /** Full route maps rebuild at most once every 10 simulation seconds. */
    private static final float refreshTicks = 600f;
    /** Changed death heat triggers an earlier rebuild, throttled to 1.5 simulation seconds. */
    private static final float heatRevisionRefreshTicks = 90f;
    private static final float trafficRefreshTicks = 120f;

    /** Weight per point of decayed death heat (strong enough to force wide detours). */
    private static final float deathRiskWeight = 1.65f;
    /** Weight per point of turret / fortification threat. */
    private static final float turretRiskWeight = 1.0f;
    /** Base danger contributed by an active enemy turret at its center. */
    private static final float baseTurretPower = 85f;
    /** Maximum single-tile fortification hazard cap (allows multi-turret killzones to weigh heavily). */
    private static final float maxTileThreat = 650f;
    private static final int maxTurretRadius = 128;

    /** Base cost penalty for tight corridors even before turrets are factored in. */
    private static final float baseChokepointPenalty = 2.2f;
    /** Multiplier amplifying turret/wall danger when trapped inside a narrow bottleneck. */
    private static final float chokepointDangerAmplifier = 1.85f;

    /** Mild crowd dispersion along wide open corridors without stalling progress. */
    private static final float congestionWeight = 2.0f;

    private static final int maxCachedRoutes = 12;
    private static final HashMap<Long, RouteField> fields = new HashMap<>();
    private static final HashMap<Integer, ThreatField> threatFields = new HashMap<>();
    private static final HashMap<Integer, TrafficField> trafficFields = new HashMap<>();
    private static Object threatWorld;
    private static int threatWidth = -1, threatHeight = -1;

    // Reusable scratch buffers for full-map Dijkstra rebuilds and threat scans (zero GC churn).
    private static float[] scratchHeat = new float[0];
    private static int[] scratchHeap = new int[0];
    private static int[] scratchPositions = new int[0];
    private static int[] scratchClearance = new int[0];
    private static float[] scratchTurretRaw = new float[0];
    private static int[] scratchTurretCount = new int[0];

    private NeoplasmStrategicPathfinder() {
    }

    /**
     * Route to an existing shared flowfield target set (e.g., enemy core, vents, ores).
     * Always evaluates the team-wide strategic route so units choose open flanking
     * valleys at map forks rather than walking into fortified chokepoints first.
     */
    public static Tile nextStep(Tile current, Team team, int costType, int pathTarget, int unitId) {
        if (current == null || team == null || Vars.world == null || RVars.pathfinderCustom == null) return null;
        PathfinderCustom.Flowfield base = RVars.pathfinderCustom.getField(team, costType, pathTarget);
        if (base == null || !base.initialized || !base.hasTargets() || base.resolution != 1) return null;

        long key = key(team, costType, pathTarget, false);
        RouteField route = getOrBuild(key, team, costType, base, null, 0f);
        return chooseNext(current, team, costType, base, route, unitId);
    }

    /**
     * Route to a specific building or target tile (used by NeoplasmFlyingAI and
     * direct-movement controllers). Uses the same cached full-map Dijkstra field
     * rooted around the destination's attack perimeter so flyers flank around
     * fortified chokepoints across the entire map.
     */
    public static Tile nextStepTo(Tile current, Tile destination, Team team, int costType, int unitId) {
        return nextStepTo(current, destination, team, costType, unitId, 0f);
    }

    public static Tile nextStepTo(Tile current, Tile destination, Team team, int costType, int unitId, float stopDistance) {
        if (current == null || destination == null || team == null || Vars.world == null
                || current == destination || destination.array() < 0) return null;

        int destIndex = destination.array();
        long key = key(team, costType, destIndex, true);
        RouteField route = getOrBuild(key, team, costType, null, destination, stopDistance);
        return chooseNext(current, team, costType, null, route, unitId);
    }

    private static long key(Team team, int costType, int targetId, boolean explicitTarget) {
        return ((long)team.id << 56)
                | ((long)(costType & 0x0f) << 52)
                | ((long)(explicitTarget ? 1 : 0) << 51)
                | (targetId & 0x7ffffffffffffL);
    }

    private static RouteField getOrBuild(long key, Team team, int costType,
                                         PathfinderCustom.Flowfield base,
                                         Tile explicitDest, float stopDistance) {
        float now = Time.time;
        int width = base != null ? base.width : Vars.world.width();
        int height = base != null ? base.height : Vars.world.height();
        int revision = NeoplasmDeathHeatmap.getRevision(team);
        int targetHash = base != null ? targetHash(base) : explicitDest.array();

        RouteField cached = fields.get(key);
        boolean heatChanged = cached != null && cached.heatRevision != revision;
        boolean heatRefreshThrottled = heatChanged && (now - cached.builtAt < heatRevisionRefreshTicks);
        if (cached != null
                && cached.base == base
                && cached.width == width && cached.height == height
                && cached.targetHash == targetHash
                && now - cached.builtAt < refreshTicks
                && (!heatChanged || heatRefreshThrottled)) {
            return cached;
        }

        RouteField rebuilt = build(team, costType, base, explicitDest, stopDistance, targetHash, revision, now);
        if (rebuilt != null) {
            if (fields.size() >= maxCachedRoutes && !fields.containsKey(key)) {
                fields.clear();
            }
            fields.put(key, rebuilt);
        }
        return rebuilt;
    }

    private static int targetHash(PathfinderCustom.Flowfield base) {
        int hash = 1;
        synchronized (base.targets) {
            for (int i = 0; i < base.targets.size; i++) {
                hash = 31 * hash + base.targets.get(i);
            }
        }
        return hash;
    }

    private static RouteField build(Team team, int costType, PathfinderCustom.Flowfield base,
                                    Tile explicitDest, float stopDistance,
                                    int targetHash, int revision, float now) {
        long perfStart = NeoplasmPerfMonitor.startTimer();
        try {
            return buildInternal(team, costType, base, explicitDest, stopDistance, targetHash, revision, now);
        } finally {
            NeoplasmPerfMonitor.endTimer(NeoplasmPerfMonitor.TIMER_ROUTE, perfStart);
        }
    }

    private static RouteField buildInternal(Team team, int costType, PathfinderCustom.Flowfield base,
                                            Tile explicitDest, float stopDistance,
                                            int targetHash, int revision, float now) {
        NeoplasmPerfMonitor.routeBuild();
        int width = base != null ? base.width : Vars.world.width();
        int height = base != null ? base.height : Vars.world.height();
        int length = width * height;
        if (length <= 0 || RVars.pathfinderCustom == null || RVars.pathfinderCustom.tiles == null
                || RVars.pathfinderCustom.tiles.length < Vars.world.width() * Vars.world.height()) return null;

        if (scratchHeat.length < length) {
            scratchHeat = new float[length];
            scratchHeap = new int[length];
            scratchPositions = new int[length];
        }

        NeoplasmDeathHeatmap.copyHeat(team, scratchHeat);
        ThreatField threat = getThreatField(team, width, height);
        float[] turretAndChoke = threat.risk;
        float[] openBonus = threat.openness;

        Pathfinder.PathCost costFunc = (costType >= 0 && costType < PathfinderCustom.costTypes.size)
                ? PathfinderCustom.costTypes.get(costType)
                : PathfinderCustom.costTypes.get(PathfinderCustom.costNone);
        int[] packedTiles = RVars.pathfinderCustom.tiles;

        float[] distance = new float[length];
        Arrays.fill(distance, Float.POSITIVE_INFINITY);
        IndexHeap frontier = new IndexHeap(length, distance, scratchHeap, scratchPositions);

        if (base != null) {
            synchronized (base.targets) {
                for (int i = 0; i < base.targets.size; i++) {
                    addTarget(base.targets.get(i), team.id, costType, costFunc, packedTiles, width, height, distance, frontier);
                }
            }
        } else if (explicitDest != null) {
            // Seed the destination and passable perimeter tiles within weapon engage range
            // so flyers naturally approach from the safest, unfortified side of the target.
            int seedRadius = Math.min(4, Math.max(1, (int)(stopDistance / Vars.tilesize) - 1));
            for (int dy = -seedRadius; dy <= seedRadius; dy++) {
                for (int dx = -seedRadius; dx <= seedRadius; dx++) {
                    if (dx * dx + dy * dy > seedRadius * seedRadius) continue;
                    int sx = explicitDest.x + dx, sy = explicitDest.y + dy;
                    if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue;
                    addTarget(sx + sy * width, team.id, costType, costFunc, packedTiles, width, height, distance, frontier);
                }
            }
            if (frontier.isEmpty()) {
                addTarget(explicitDest.array(), team.id, costType, costFunc, packedTiles, width, height, distance, frontier);
            }
        }

        if (frontier.isEmpty()) return null;

        final int[] dx = {0, 1, 0, -1, 1, 1, -1, -1};
        final int[] dy = {1, 0, -1, 0, 1, -1, -1, 1};

        while (!frontier.isEmpty()) {
            int index = frontier.pop();
            float nodeCost = distance[index];

            int x = index % width;
            int y = index / width;

            for (int d = 0; d < 8; d++) {
                int nx = x + dx[d], ny = y + dy[d];
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                int next = nx + ny * width;
                if (!isPassable(next, team.id, costType, costFunc, packedTiles)) continue;

                if (dx[d] != 0 && dy[d] != 0) {
                    int horizontal = (x + dx[d]) + y * width;
                    int vertical = x + (y + dy[d]) * width;
                    if (!isPassable(horizontal, team.id, costType, costFunc, packedTiles)
                            || !isPassable(vertical, team.id, costType, costFunc, packedTiles)) continue;
                }

                float terrain = costFunc.getCost(team.id, packedTiles[next]);
                if (terrain < 1f) terrain = 1f;

                // Amplify death heat in narrow corridors where units cannot spread out.
                float chokeMultiplier = 1f + openBonus[next] * 0.75f;
                float risk = scratchHeat[next] * deathRiskWeight * chokeMultiplier
                        + turretAndChoke[next] * turretRiskWeight;

                float step = (terrain + risk) * (dx[d] != 0 && dy[d] != 0 ? 1.41421356f : 1f);
                float candidate = nodeCost + step;

                if (candidate + 0.001f < distance[next]) {
                    distance[next] = candidate;
                    frontier.addOrDecrease(next);
                }
            }
        }

        return new RouteField(base, width, height, distance, revision, targetHash, now);
    }

    private static boolean isPassable(int pos, int teamId, int costType,
                                      Pathfinder.PathCost costFunc, int[] packedTiles) {
        if (pos < 0 || pos >= packedTiles.length) return false;
        int amount = costFunc.getCost(teamId, packedTiles[pos]);
        return amount != PathfinderCustom.impassable
                && !(costType == PathfinderCustom.costNaval && amount >= 6000);
    }

    private static void addTarget(int tileArray, int teamId, int costType,
                                  Pathfinder.PathCost costFunc, int[] packedTiles,
                                  int width, int height,
                                  float[] distance, IndexHeap frontier) {
        if (tileArray < 0 || tileArray >= width * height) return;
        if (!isPassable(tileArray, teamId, costType, costFunc, packedTiles)) return;
        if (distance[tileArray] > 0f) {
            distance[tileArray] = 0f;
            frontier.addOrDecrease(tileArray);
        }
    }

    private static void buildTurretRisk(Team team, float[] risk, float[] openness, int width, int height) {
        long perfStart = NeoplasmPerfMonitor.startTimer();
        try {
            buildTurretRiskInternal(team, risk, openness, width, height);
        } finally {
            NeoplasmPerfMonitor.endTimer(NeoplasmPerfMonitor.TIMER_TURRET, perfStart);
        }
    }

    /**
     * Computes a combined map-wide fortification & terrain-narrowness field:
     *  - Two-pass distance transform measures corridor width / openness at every tile.
     *  - Enemy turrets add range-weighted hazard, scaled by turret tier/ammo and
     *    amplified when multiple turrets create overlapping crossfire.
     *  - Enemy defensive walls and shield projectors mark fortified frontlines.
     *  - Turret/wall danger inside narrow bottlenecks is multiplied so fortified
     *    chokepoints are strongly avoided in favor of wider, open flanking routes.
     */
    private static void buildTurretRiskInternal(Team team, float[] risk, float[] openness, int width, int height) {
        NeoplasmPerfMonitor.turretScan();
        int length = width * height;
        if (scratchClearance.length < length) {
            scratchClearance = new int[length];
            scratchTurretRaw = new float[length];
            scratchTurretCount = new int[length];
        }
        Arrays.fill(scratchTurretRaw, 0, length, 0f);
        Arrays.fill(scratchTurretCount, 0, length, 0);

        // 1. Fast O(W*H) two-pass distance transform from impassable terrain & enemy walls
        //    to identify narrow chokepoints vs. wide-open flanking terrain.
        int maxDist = 7;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                Tile tile = Vars.world.tile(x, y);
                boolean blocked = tile == null
                        || tile.floor().isDeep()
                        || (tile.solid() && (tile.team() == Team.derelict || tile.team() != team));
                if (blocked) {
                    scratchClearance[idx] = 0;
                } else {
                    int left = x > 0 ? scratchClearance[idx - 1] : 0;
                    int up = y > 0 ? scratchClearance[idx - width] : 0;
                    scratchClearance[idx] = Math.min(maxDist, Math.min(left, up) + 1);
                }
            }
        }
        for (int y = height - 1; y >= 0; y--) {
            int row = y * width;
            for (int x = width - 1; x >= 0; x--) {
                int idx = row + x;
                int cur = scratchClearance[idx];
                if (cur > 0) {
                    int right = x + 1 < width ? scratchClearance[idx + 1] : 0;
                    int down = y + 1 < height ? scratchClearance[idx + width] : 0;
                    cur = Math.min(cur, Math.min(right, down) + 1);
                    scratchClearance[idx] = cur;
                }
                // chokepointFactor: 1.0 in tight 1-tile bottlenecks, 0.0 in wide open areas (clearance >= 6)
                openness[idx] = Math.max(0f, (6f - Math.min(6, cur)) / 5f);
            }
        }

        // 2. Scan enemy turrets, shields, and wall lines once per building.
        Groups.build.each(build -> {
            if (build == null || !build.isValid() || build.team == team || build.team == Team.derelict) return;

            if (build instanceof Turret.TurretBuild turretBuild) {
                float range = Math.max(0f, turretBuild.range());
                int radius = Math.min(maxTurretRadius, (int)Math.ceil(range / Vars.tilesize));
                if (radius <= 0) return;

                float ammoScale = turretBuild.hasAmmo() ? 1f : 0.35f;
                // Larger turrets (3x3, 4x4) and long-range artillery pose greater chokepoint danger.
                float tierScale = 0.85f + 0.25f * build.block.size + Math.min(0.6f, range / 400f);
                float power = baseTurretPower * ammoScale * tierScale;
                int tx = turretBuild.tile.x;
                int ty = turretBuild.tile.y;

                for (int dy = -radius; dy <= radius; dy++) {
                    int y = ty + dy;
                    if (y < 0 || y >= height) continue;
                    for (int dx = -radius; dx <= radius; dx++) {
                        int x = tx + dx;
                        if (x < 0 || x >= width) continue;

                        float distanceWorld = (float)Math.sqrt(dx * dx + dy * dy) * Vars.tilesize;
                        if (distanceWorld > range) continue;

                        float falloff = 1f - distanceWorld / Math.max(range, 1f);
                        // Non-trivial firing envelope cost right from the outer range boundary
                        float envelope = 0.22f + 0.78f * falloff * falloff;
                        int index = x + y * width;
                        scratchTurretRaw[index] += power * envelope;
                        scratchTurretCount[index]++;
                    }
                }
            } else if (build instanceof ForceProjector.ForceBuild || build instanceof BaseShield.BaseShieldBuild) {
                int radius = 10;
                int tx = build.tile.x, ty = build.tile.y;
                for (int dy = -radius; dy <= radius; dy++) {
                    int y = ty + dy;
                    if (y < 0 || y >= height) continue;
                    for (int dx = -radius; dx <= radius; dx++) {
                        int x = tx + dx;
                        if (x < 0 || x >= width) continue;
                        float d = (float)Math.sqrt(dx * dx + dy * dy);
                        if (d > radius) continue;
                        float falloff = 1f - d / (radius + 1f);
                        scratchTurretRaw[x + y * width] += 36f * falloff;
                    }
                }
            } else if (build instanceof Wall.WallBuild || build.block instanceof Wall) {
                // Enemy wall lines mark fortified choke fronts (affects both ground and flyers).
                int radius = 4;
                int tx = build.tile.x, ty = build.tile.y;
                float wallStrength = Math.min(2.5f, build.maxHealth / 400f);
                for (int dy = -radius; dy <= radius; dy++) {
                    int y = ty + dy;
                    if (y < 0 || y >= height) continue;
                    for (int dx = -radius; dx <= radius; dx++) {
                        int x = tx + dx;
                        if (x < 0 || x >= width) continue;
                        float d = (float)Math.sqrt(dx * dx + dy * dy);
                        if (d > radius) continue;
                        float falloff = 1f - d / (radius + 1f);
                        scratchTurretRaw[x + y * width] += 18f * wallStrength * falloff;
                    }
                }
            }
        });

        // 3. Combine overlapping turret crossfire with chokepoint narrowness.
        for (int i = 0; i < length; i++) {
            float raw = scratchTurretRaw[i];
            int count = scratchTurretCount[i];
            if (count > 1) {
                // Overlapping crossfire is disproportionately lethal
                raw *= (1f + 0.45f * (count - 1));
            }
            float choke = openness[i];
            float combined = choke * baseChokepointPenalty + raw * (1f + choke * chokepointDangerAmplifier);
            risk[i] = Math.min(maxTileThreat, combined);
        }
    }

    private static Tile chooseNext(Tile current, Team team, int costType,
                                   PathfinderCustom.Flowfield base,
                                   RouteField route, int unitId) {
        if (route == null || current == null) return null;
        int x = current.x, y = current.y;
        if (x < 0 || y < 0 || x >= route.width || y >= route.height) return null;
        int index = x + y * route.width;
        if (index < 0 || index >= route.distance.length || Float.isInfinite(route.distance[index])) return null;

        Pathfinder.PathCost costFunc = (costType >= 0 && costType < PathfinderCustom.costTypes.size)
                ? PathfinderCustom.costTypes.get(costType)
                : PathfinderCustom.costTypes.get(PathfinderCustom.costNone);
        int[] packedTiles = RVars.pathfinderCustom != null ? RVars.pathfinderCustom.tiles : null;
        if (packedTiles == null || packedTiles.length < route.width * route.height) return null;

        float[] traffic = getTrafficField(team, route.width, route.height).density;
        int strategicIndex = -1;
        float strategicBest = Float.POSITIVE_INFINITY;

        for (Point2 offset : Geometry.d8) {
            int nx = x + offset.x, ny = y + offset.y;
            if (nx < 0 || ny < 0 || nx >= route.width || ny >= route.height) continue;
            int next = nx + ny * route.width;
            if (!isPassable(next, team.id, costType, costFunc, packedTiles)) continue;

            if (offset.x != 0 && offset.y != 0) {
                if (!isPassable((x + offset.x) + y * route.width, team.id, costType, costFunc, packedTiles)
                        || !isPassable(x + (y + offset.y) * route.width, team.id, costType, costFunc, packedTiles)) {
                    continue;
                }
            }

            float routeCost = route.distance[next];
            if (routeCost >= route.distance[index]) continue;

            float crowd = Math.min(3f, traffic[next]);
            float tie = ((unitId * 31 + next * 17) & 7) * 0.015625f;
            float score = routeCost + crowd * congestionWeight + tie;
            if (score < strategicBest) {
                strategicBest = score;
                strategicIndex = next;
            }
        }

        if (strategicIndex < 0 || strategicIndex == index) return null;
        return Vars.world.tile(strategicIndex);
    }

    /** Shared turret + chokepoint exposure map; one scan serves all ground and flying units. */
    private static ThreatField getThreatField(Team team, int width, int height) {
        float now = Time.time;
        if (threatWorld != Vars.world || threatWidth != width || threatHeight != height) {
            threatFields.clear();
            threatWorld = Vars.world;
            threatWidth = width;
            threatHeight = height;
        }

        int teamId = team.id;
        ThreatField cached = threatFields.get(teamId);
        if (cached != null && now - cached.builtAt < refreshTicks) return cached;

        int size = width * height;
        float[] risk = new float[size];
        float[] openness = new float[size];
        buildTurretRisk(team, risk, openness, width, height);
        ThreatField rebuilt = new ThreatField(risk, openness, now);
        threatFields.put(teamId, rebuilt);
        return rebuilt;
    }

    private static TrafficField getTrafficField(Team team, int width, int height) {
        float now = Time.time;
        TrafficField cached = trafficFields.get(team.id);
        if (cached != null && cached.width == width && cached.height == height
                && now - cached.builtAt < trafficRefreshTicks) {
            return cached;
        }

        NeoplasmPerfMonitor.trafficScan();
        long perfStart = NeoplasmPerfMonitor.startTimer();
        try {
            float[] density = new float[width * height];
            Groups.unit.each(other -> {
                if (other == null || other.team != team || other.dead() || !other.isAdded()) return;
                Tile tile = other.tileOn();
                if (tile == null) return;

                int index = tile.array();
                if (index < 0 || index >= density.length) return;
                density[index] += 1f;
            });

            TrafficField rebuilt = new TrafficField(density, width, height, now);
            trafficFields.put(team.id, rebuilt);
            return rebuilt;
        } finally {
            NeoplasmPerfMonitor.endTimer(NeoplasmPerfMonitor.TIMER_TRAFFIC, perfStart);
        }
    }

    private static final class TrafficField {
        final float[] density;
        final int width, height;
        final float builtAt;

        TrafficField(float[] density, int width, int height, float builtAt) {
            this.density = density;
            this.width = width;
            this.height = height;
            this.builtAt = builtAt;
        }
    }

    private static final class ThreatField {
        final float[] risk;
        final float[] openness;
        final float builtAt;

        ThreatField(float[] risk, float[] openness, float builtAt) {
            this.risk = risk;
            this.openness = openness;
            this.builtAt = builtAt;
        }
    }

    private static final class RouteField {
        final PathfinderCustom.Flowfield base;
        final int width, height;
        final float[] distance;
        final int heatRevision, targetHash;
        final float builtAt;

        RouteField(PathfinderCustom.Flowfield base, int width, int height, float[] distance,
                   int heatRevision, int targetHash, float builtAt) {
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
     * Indexed binary min-heap backed by caller-provided scratch arrays.
     */
    private static final class IndexHeap {
        private final int[] heap;
        private final int[] positions;
        private final float[] priorities;
        private int size;

        IndexHeap(int capacity, float[] priorities, int[] heapBuffer, int[] posBuffer) {
            heap = heapBuffer;
            positions = posBuffer;
            Arrays.fill(positions, 0, capacity, -1);
            this.priorities = priorities;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void addOrDecrease(int index) {
            int position = positions[index];
            if (position == -2) return;
            if (position < 0) {
                int at = size++;
                heap[at] = index;
                positions[index] = at;
                siftUp(at);
            } else {
                siftUp(position);
            }
        }

        int pop() {
            int result = heap[0];
            positions[result] = -2;
            int last = heap[--size];
            if (size > 0) {
                heap[0] = last;
                positions[last] = 0;
                siftDown(0);
            }
            return result;
        }

        private void siftUp(int at) {
            while (at > 0) {
                int parent = (at - 1) >>> 1;
                if (priorities[heap[parent]] <= priorities[heap[at]]) break;
                swap(parent, at);
                at = parent;
            }
        }

        private void siftDown(int at) {
            while (true) {
                int left = at * 2 + 1;
                if (left >= size) return;
                int right = left + 1;
                int smallest = right < size && priorities[heap[right]] < priorities[heap[left]]
                        ? right : left;
                if (priorities[heap[at]] <= priorities[heap[smallest]]) return;
                swap(at, smallest);
                at = smallest;
            }
        }

        private void swap(int first, int second) {
            int a = heap[first], b = heap[second];
            heap[first] = b;
            heap[second] = a;
            positions[a] = second;
            positions[b] = first;
        }
    }

    /** Returns the cached fortification/turret threat at a tile (0 if safe). */
    public static float getTileThreat(Team team, Tile tile) {
        if (team == null || tile == null || Vars.world == null) return 0f;
        int width = Vars.world.width();
        int height = Vars.world.height();
        int idx = tile.array();
        if (idx < 0 || idx >= width * height) return 0f;
        ThreatField threat = getThreatField(team, width, height);
        return threat != null && idx < threat.risk.length ? threat.risk[idx] : 0f;
    }

    /** Returns corridor narrowness [0..1], where 1 is a tight chokepoint. */
    public static float getTileChokepoint(Team team, Tile tile) {
        if (team == null || tile == null || Vars.world == null) return 0f;
        int width = Vars.world.width();
        int height = Vars.world.height();
        int idx = tile.array();
        if (idx < 0 || idx >= width * height) return 0f;
        ThreatField threat = getThreatField(team, width, height);
        return threat != null && idx < threat.openness.length ? threat.openness[idx] : 0f;
    }

    /** Returns cached strategic route distance to a shared flowfield target. */
    public static float getStrategicDistance(Tile tile, Team team, int costType, int pathTarget) {
        if (tile == null || team == null || Vars.world == null || RVars.pathfinderCustom == null) {
            return Float.POSITIVE_INFINITY;
        }
        PathfinderCustom.Flowfield base = RVars.pathfinderCustom.getField(team, costType, pathTarget);
        if (base == null || !base.initialized || !base.hasTargets() || base.resolution != 1) {
            return Float.POSITIVE_INFINITY;
        }
        long key = key(team, costType, pathTarget, false);
        RouteField route = getOrBuild(key, team, costType, base, null, 0f);
        int idx = tile.array();
        if (route == null || idx < 0 || idx >= route.distance.length) return Float.POSITIVE_INFINITY;
        return route.distance[idx];
    }

    public static void clear() {
        fields.clear();
        threatFields.clear();
        trafficFields.clear();
        threatWorld = null;
        threatWidth = -1;
        threatHeight = -1;
    }
}
