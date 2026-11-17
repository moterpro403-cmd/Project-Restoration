package classicMod.library.blocks.neoplasiaBlocks;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.graphics.g2d.*;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import classicMod.content.*;
import classicMod.library.ai.*;
import mindustry.Vars;
import mindustry.ai.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.gen.Building;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.Autotiler;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.meta.*;

import java.awt.*;

import static classicMod.content.RVars.CordCanDrill;
import static classicMod.content.RVars.pathfinderCustom;
import static mindustry.Vars.itemSize;

public class CausticCord extends NeoplasmBlock implements Autotiler {
    public TextureRegion[] tiles;
    public TextureRegion[][] regions;

    public boolean source = false;


    public CausticCord(String name) {
        super(name);

        group = BlockGroup.transportation;
        hasItems = true;
        targetable = true;
        solid = false;
        unloadable = false;

        rotate = false;
        isCord = true;

        itemCapacity = 1;
        liquidCapacity = 50f;
        priority = -1.0F;
        //envEnabled = 7;
        noUpdateDisabled = false;
    }

    @Override
    public void init() {
        tiles = new TextureRegion[46];
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = Core.atlas.find(name + "-" + i);
        }
        super.init();
    }

    public boolean blendsArmored(Tile tile, int rotation, int otherx, int othery, int otherrot, Block otherblock) {
        return Point2.equals(tile.x + Geometry.d4(rotation).x, tile.y + Geometry.d4(rotation).y, otherx, othery) || !otherblock.rotatedOutput(otherx, othery) && Edges.getFacingEdge(otherblock, otherx, othery, tile) != null && Edges.getFacingEdge(otherblock, otherx, othery, tile).relativeTo(tile) == rotation || otherblock.rotatedOutput(otherx, othery) && otherblock instanceof CausticCord && Point2.equals(otherx + Geometry.d4(otherrot).x, othery + Geometry.d4(otherrot).y, tile.x, tile.y);
    }

    public TextureRegion[] icons() {
        return new TextureRegion[]{Core.atlas.find(name + "-0")};
    }

    public boolean blends(Tile tile, int rotation, int otherx, int othery, int otherrot, Block otherblock) {
        return otherblock.outputsItems() && this.blendsArmored(tile, rotation, otherx, othery, otherrot, otherblock) || this.lookingAt(tile, rotation, otherx, othery, otherblock) && otherblock.hasItems;
    }

    public class CordBuild extends NeoplasmBuilding {

        /** Derived vascular state, refreshed by NeoplasmCordNetwork. */
        public boolean connected;
        public int hopDistance = Integer.MAX_VALUE;
        public float vascularPressure;
        public int connectionMask;

        /** Refresh visual adjacency without changing the existing transport interfaces. */
        public void refreshConnections() {
            int mask = 0;
            for (int i = 0; i < 4; i++) {
                Point2 direction = Geometry.d4[i];
                Tile other = Vars.world.tile(tile.x + direction.x, tile.y + direction.y);
                if (other == null || other.build == null || other.build.team != team) continue;
                if (other.build instanceof CordBuild
                    || other.build instanceof CausticHeart.HeartBuilding
                    || other.build instanceof NeoplasmBuilding) {
                    mask |= (1 << i);
                }
            }
            connectionMask = mask;
        }

        //TODO make it work YIPPE
        int facingRot = 1;
        public float progress;
        // Counts unsuccessful growth attempts; a blocked route should trigger
        // a new destination, not destroy the cord.
        public int growRestart = 0;

        // Runs at a low frequency so a large cord network does not flood the
        // mobile game with path searches.
        private float networkRepairTimer = 0f;
        private static final float networkRepairInterval = 180f;

        // Each cord keeps its own expansion cooldown, independent of other cords.
        private static final float cordGrowthInterval = 60f;
        private float nextCordGrowthTime = -1f;
        

        public Seq<Tile> Queue = new Seq<>();

        @Nullable
        public Item current;
        public int blendbits;
        public int xscl;
        public int yscl;

        public int task = 0;

        @Nullable
        public CordBuild prev;

        int[] bitmask = new int[]{
                39, 36, 39, 36, 27, 16, 27, 24, 39, 36, 39, 36, 27, 16, 27, 24,
                38, 37, 38, 37, 17, 41, 17, 43, 38, 37, 38, 37, 26, 21, 26, 25,
                39, 36, 39, 36, 27, 16, 27, 24, 39, 36, 39, 36, 27, 16, 27, 24,
                38, 37, 38, 37, 17, 41, 17, 43, 38, 37, 38, 37, 26, 21, 26, 25,
                3,  4,  3,  4, 15, 40, 15, 20,  3,  4,  3,  4, 15, 40, 15, 20,
                5, 28,  5, 28, 29, 10, 29, 23,  5, 28,  5, 28, 31, 11, 31, 32,
                3,  4,  3,  4, 15, 40, 15, 20,  3,  4,  3,  4, 15, 40, 15, 20,
                2, 30,  2, 30,  9, 46,  9, 22,  2, 30,  2, 30, 14, 44, 14,  6,
                39, 36, 39, 36, 27, 16, 27, 24, 39, 36, 39, 36, 27, 16, 27, 24,
                38, 37, 38, 37, 17, 41, 17, 43, 38, 37, 38, 37, 26, 21, 26, 25,
                39, 36, 39, 36, 27, 16, 27, 24, 39, 36, 39, 36, 27, 16, 27, 24,
                38, 37, 38, 37, 17, 41, 17, 43, 38, 37, 38, 37, 26, 21, 26, 25,
                3,  0,  3,  0, 15, 42, 15, 12,  3,  0,  3,  0, 15, 42, 15, 12,
                5,  8,  5,  8, 29, 35, 29, 33,  5,  8,  5,  8, 31, 34, 31,  7,
                3,  0,  3,  0, 15, 42, 15, 12,  3,  0,  3,  0, 15, 42, 15, 12,
                2,  1,  2,  1,  9, 45,  9, 19,  2,  1,  2,  1, 14, 18, 14, 13
        };


        @Override
        public void handleItem(Building source, Item item) {
            current = item;
            super.handleItem(source, item);
        }

        @Override
        public boolean acceptItem(Building source, Item item) {
            //handleItem(source, item);
            return !items.any();
        }

        @Override
        public float rotdeg() {
            return (float)(this.rotation * 90);
        }

        @Override
        public void draw() {
            float rotation = this.rotdeg();

            Draw.z(Layer.blockUnder);
            if (current != null){
                Draw.z(Layer.blockUnder + 0.1f);
                Draw.color();
                Draw.scl();
                Draw.rect(current.fullIcon, x, y, itemSize, itemSize);
            }



            // Get the sprite from the atlas
            //Draw.rect(, x, y, rotation);
            TextureRegion region = sliced(Core.atlas.find(name + "-" + blendbits), SliceMode.none);
            drawAt(x, y, blendbits, rotation, SliceMode.none);
            Draw.color();

            Draw.reset();
        }

        boolean validBuilding(Building dest, Item item){
            if (item == null || dest == null || dest.team != this.team) return false;

            // A Heart is the Graphite sink for Blob production; never strand
            // unrelated ores there. Other consumers decide through acceptItem.
            if (dest instanceof CausticHeart.HeartBuilding && item != Items.graphite) return false;

            return dest.acceptItem(this, item);
        }

        @Override
        public void growCord(Block block) {
            if (nextCordGrowthTime >= 0f && Time.time < nextCordGrowthTime) {
                grow = false;
                return;
            }

            float pressureScale = connected
                ? Mathf.clamp(vascularPressure, 0.75f, 1.35f)
                : 1f;
            nextCordGrowthTime = Time.time + cordGrowthInterval / pressureScale;

            Tile origin = tile;
            if (origin == null || origin == Vars.emptyTile || Vars.world == null) {
                grow = false;
                return;
            }

            growRestart++;

            // A dead-end vent/ore field must not freeze the colony. Try the
            // current objective first, then the other two valid colony targets.
            int requestedTask = task == 0 ? PathfinderExtended.fieldVent : task;
            int[] routeTasks = {
                requestedTask,
                PathfinderExtended.fieldVent,
                PathfinderExtended.fieldOres,
                Pathfinder.fieldCore
            };
            Tile next = null;
            int selectedTask = requestedTask;
            for (int i = 0; i < routeTasks.length; i++) {
                int routeTask = routeTasks[i];
                boolean duplicate = false;
                for (int j = 0; j < i; j++) {
                    if (routeTasks[j] == routeTask) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;

                Tile candidate = pathfind(routeTask);
                if (candidate != null && candidate != origin && passable(candidate, true)) {
                    next = candidate;
                    selectedTask = routeTask;
                    break;
                }
            }

            // Flowfields can legitimately have no targets on small/custom
            // maps. Continue down the vein's heading, then choose the nearest
            // open side tile rather than permanently stalling at the last cord.
            if (next == null) {
                Point2 forward = Geometry.d4[Mathf.mod(facingRot, 4)];
                Tile forwardTile = Vars.world.tile(origin.x + forward.x, origin.y + forward.y);
                if (forwardTile != null && forwardTile.build == null && passable(forwardTile, true)) {
                    next = forwardTile;
                } else {
                    Tile bestLocal = null;
                    float bestScore = Float.MAX_VALUE;
                    for (Point2 direction : Geometry.d8) {
                        Tile candidate = Vars.world.tile(origin.x + direction.x, origin.y + direction.y);
                        if (candidate == null || candidate.build != null || !passable(candidate, true)) continue;
                        if (prev != null && prev.tile == candidate) continue;
                        float headingPenalty = 1f - (direction.x * forward.x + direction.y * forward.y) * 0.5f;
                        float score = headingPenalty * 12f + candidate.dst(origin);
                        if (score < bestScore) {
                            bestScore = score;
                            bestLocal = candidate;
                        }
                    }
                    next = bestLocal;
                }
            }

            if (next == null) {
                grow = false;
                return;
            }
            task = selectedTask;

            // A diagonal flowfield step can be bridged by either orthogonal
            // tile. A cardinal step can be placed directly; do not require
            // spare tiles on both sides or the colony stalls in narrow lanes.
            Seq<Tile> nearTiles = new Seq<>(4);
            for (Point2 direction : Geometry.d4) {
                Tile candidate = Vars.world.tile(next.x + direction.x, next.y + direction.y);
                if (candidate == null) continue;

                int rotation = origin.relativeTo(candidate);
                if (rotation < 0) continue;
                Tile sideA = candidate.nearby(Mathf.mod(rotation + 1, 4));
                Tile sideB = candidate.nearby(Mathf.mod(rotation - 1, 4));
                if (!passable(candidate, true)) continue;
                if (candidate.build != null && !(candidate.build instanceof CordBuild)) continue;

                // Prefer open elbow tiles, but permit a tight corridor when
                // the flowfield says it is the only route forward.
                if (passable(sideA, false) || passable(sideB, false)) {
                    if (!nearTiles.contains(candidate)) nearTiles.add(candidate);
                }
            }

            if (nearTiles.isEmpty()) {
                int rotation = origin.relativeTo(next);
                if (rotation >= 0 && passable(next, true)
                    && (next.build == null || next.build instanceof CordBuild)) {
                    nearTiles.add(next);
                }
            }

            if (!nearTiles.isEmpty()) {
                Tile objective = null;
                if (task == PathfinderExtended.fieldVent) objective = getClosestVent();
                else if (task == PathfinderExtended.fieldOres) objective = getClosestOre();

                if (objective != null) {
                    Tile finalObjective = objective;
                    nearTiles.sort(candidate -> cordRouteScore(candidate, finalObjective));
                } else {
                    // A valid flowfield step is already progress even when the
                    // map has no free vent/ore tile to use as a secondary target.
                    Tile finalNext = next;
                    nearTiles.sort(candidate -> candidate.dst(finalNext));
                }

                Tile chosen = nearTiles.first();
                int rotation = origin.relativeTo(chosen);
                if (rotation >= 0) {
                    if (chosen.build == null && !CantReplace(chosen.block())) {
                        chosen.setBlock(RBlocks.cord, team);
                    }
                    if (chosen.build instanceof CordBuild cordBuild && cordBuild != this) {
                        cordBuild.task = Mathf.randomBoolean(0.98f) ? task
                            : (Mathf.randomBoolean(0.5f)
                                ? PathfinderExtended.fieldOres : PathfinderExtended.fieldVent);
                        cordBuild.facingRot = rotation;
                        cordBuild.prev = this;
                        growRestart = 0;
                    }
                }
            }

            super.growCord(block);
        }

        private float cordRouteScore(Tile candidate, Tile objective) {
            // Legacy cord growth follows the nearest useful objective.
            return candidate.dst(objective);
        }

        public Tile pathfind(int pathTarget) {
            int costType = Pathfinder.costGround;
            Tile tile = this.tile;
            if (tile != null) {
                Tile targetTile = Vars.pathfinder.getTargetTile(tile, Vars.pathfinder.getField(team, costType, pathTarget));
                if (tile != targetTile && (costType != 2 || targetTile.floor().isLiquid)) {
                    return targetTile;
                }
            }
            return null;
        }
        @Nullable
        public Tile getClosestOre() {
            Seq<Tile> available = PathfinderExtended.Ores.copy();
            available.removeAll(ore -> ore == null || ore.build != null
                || (!RVars.CordCanDrill.contains(ore.drop())
                    && !RVars.CordCanDrill.contains(ore.wallDrop())));
            return Geometry.findClosest(x, y, available);
        }


        @Nullable
        public Tile getClosestVent() {
            Seq<Tile> avaliableVents = PathfinderExtended.SteamVents.copy().removeAll(tile -> tile.build instanceof CausticHeart.HeartBuilding);
            Tile vent = Geometry.findClosest(x, y, avaliableVents);
            return (vent != null && !(vent.build instanceof CausticHeart.HeartBuilding)) ? vent : null;
        }

        @Override
        public void update() {
            super.update();
            if(tile == null || tile == Vars.emptyTile || Vars.world == null){
                networkRepairTimer = 0f;
                return;
            }

            // Reconnect isolated cord components to the nearest Heart-fed branch.
            // Unconnected sections are kept alive by NeoplasmBlock while this runs.
            if (grown && !NeoplasmCordNetwork.isConnected(team, tile)) {
                networkRepairTimer += delta();
                if (networkRepairTimer >= networkRepairInterval) {
                    networkRepairTimer = 0f;
                    NeoplasmCordNetwork.requestReconnect(tile, team, 24);
                }
            } else {
                networkRepairTimer = 0f;
            }

            if (Queue.size > 0) coverQueue(pipe);

            // Failed expansion is recoverable. Cycle through the same three
            // destinations as the PC cord AI instead of blacklisting the parent.
            if (growRestart >= 2) {
                if (task == PathfinderExtended.fieldVent) {
                    task = PathfinderExtended.fieldOres;
                } else if (task == PathfinderExtended.fieldOres) {
                    task = Pathfinder.fieldCore;
                } else {
                    task = PathfinderExtended.fieldVent;
                }
                growRestart = 0;
            }
            this.block.nearbySide(tile.x, tile.y, Mathf.mod(facingRot, 4), 0, Tmp.p1);
            int dx = (Geometry.d4x(facingRot) > 0) ? 1 : 0;
            int dy = (Geometry.d4y(facingRot) > 0) ? 1 : 0;
            Tile other = Vars.world.tile(Tmp.p1.x + dx, Tmp.p1.y + dy);
            if (other != null && other.solid()) {
                int spaces = calculateSpaces(drill.size, other.x, other.y);
                Item drop = other.wallDrop();
                if (drop != null && spaces >= drill.size * drill.size) {
                    tile.setBlock(RBlocks.neoplasiaDrill, team);
                }
            }
        }

        public int getTotal(Item item, int size){
            int total = 0;
            for (int dy = -size; dy < size; dy++) {
                for (int dx = -size; dx < size; dx++) {
                    Tile tileOn = Vars.world.tile(tile.x + dx, tile.y + dy);
                    if (tileOn != null){
                        if (tileOn.build != null && tileOn.build instanceof CordBuild cordBuild){
                            total += (cordBuild.items.has(item)) ? 1 : 0;
                        }
                    }
                }
            }
            return total;
        }

        @Override
        public void updateBeat() {
            boolean cordMode = true;
            if (grow) { //TODO some AI strategy block
                if (
                        (items.has(Items.beryllium) &&
                        left() == null &&
                        right() == null)
                        ||
                                (getTotal(Items.beryllium, 3) >= 5)
                ){
                    if (Mathf.chance(0.32f)) ReplaceTo(RBlocks.scolopendraSpawner);
                    else if (Mathf.randomBoolean(0.5f)) ReplaceTo(RBlocks.renaleSpawner);
                    else if (Mathf.randomBoolean(0.5f)) ReplaceTo(RBlocks.walkySpawner);
                    else ReplaceTo(RBlocks.oxideCrafter);
                    cordMode = false;
                }

                if (cordMode && (
                        (items.has(Items.graphite) &&
                        left() == null &&
                        right() == null)
                        ||
                                ((getTotal(Items.graphite, 3) >= 5))

                )){
                    if ((items.has(Items.oxide) || getTotal(Items.oxide, 3) >= 5)
                            && Mathf.chance(0.25f)) ReplaceTo(RBlocks.polyctenidSpawner);
                    else if (Mathf.chance(0.12f)) ReplaceTo(RBlocks.siegeCrawlerSpawner);
                    else if (Mathf.randomBoolean(0.5f)) ReplaceTo(RBlocks.muleSpawner);
                    else ReplaceTo(RBlocks.squidSpawner);
                    cordMode = false;
                }

                if (cordMode && (
                        (items.has(Items.oxide) &&
                        left() == null &&
                        right() == null)
                        ||
                                ((getTotal(Items.oxide, 3) >= 5))
                )){

                    if (Mathf.chance(0.30f)) ReplaceTo(RBlocks.mycelidSpawner);
                    else if (Mathf.chance(0.10f)) ReplaceTo(RBlocks.sporeCarrierSpawner);
                    else ReplaceTo(RBlocks.hydroBomberSpawner);
                    cordMode = false;
                }

                if (cordMode && (Units.closestEnemy(team, x, y, 220f, u -> u.type.killable && u.type.hittable && u.isGrounded()) != null)) {
                    boolean tooClose = Units.closestBuilding(team, x, y, 60f, b -> (b.block == RBlocks.neoplasiaBomb)) != null;
                    if (!tooClose &&
                            left() == null &&
                            right() == null
                    ) {
                        ReplaceTo(RBlocks.neoplasiaBomb);
                        cordMode = false;
                    }
                }

                if (cordMode && (Units.closestEnemy(team, x, y, 440f, u -> u.type.killable && u.type.hittable && u.range() > 240f) != null) ||
                        (Units.findEnemyTile(team, x, y, 440f, b -> b.isValid() && (
                                b instanceof Turret.TurretBuild turretBuild && turretBuild.range() >= 200f)
                        ) != null)) {
                    boolean tooClose = Units.closestBuilding(team, x, y, 440f, b -> (b instanceof CausticTurret.CausticTurretBuild && b.block == RBlocks.pore)) != null;
                    if (!tooClose) {
                        ReplaceTo(RBlocks.pore);
                        cordMode = false;
                    }
                }

                if (cordMode && (Units.closestEnemy(team, x, y, 120f, u -> u.type.killable && u.type.hittable && u.range() > 120f) != null) ||
                        (Units.findEnemyTile(team, x, y, 140f, b -> b.isValid() && (
                                b instanceof Turret.TurretBuild turretBuild && turretBuild.range() >= 80f)
                        ) != null)) {
                    boolean tooClose = Units.closestBuilding(team, x, y, 120f, b -> (b instanceof CausticTurret.CausticTurretBuild && b.block == RBlocks.bloom)) != null;
                    if (!tooClose) {
                        ReplaceTo(RBlocks.bloom);
                        cordMode = false;
                    }
                }

                if ((Units.closestEnemy(team, x, y, 30f, u -> u.type.killable && u.type.hittable && u.range() > 30f) != null) ||
                        (Units.findEnemyTile(team, x, y, 30f, b -> b.isValid() && (
                                b instanceof Turret.TurretBuild turretBuild && turretBuild.range() >= 30f)
                        ) != null)) {
                    boolean tooClose = Units.closestBuilding(team, x, y, 30f, b -> (b instanceof CausticTurret.CausticTurretBuild && b.block == RBlocks.tole)) != null;
                    if (!tooClose) {
                        ReplaceTo(RBlocks.tole);
                        cordMode = false;
                    }
                } if (cordMode) growCord(RBlocks.cord);
            }
            super.updateBeat();
        }

        @Override
        public void updateAfterBeat() {
            Item item = items.first();
            if (item == null) {
                current = null;
                return;
            }

            Seq<NeoplasmBuilding> spawnerDestinations = new Seq<>();
            Seq<NeoplasmBuilding> otherDestinations = new Seq<>();

            // Use actual footprint adjacency. Feed a spawner only when it
            // accepts this exact ingredient; otherwise continue along the vein.
            for (Building nearby : proximity) {
                NeoplasmBuilding destination = getNeoplasm(nearby);
                if (destination == null || destination == this || !validBuilding(destination, item)) continue;
                if (destination.block instanceof CausticSpawner) {
                    if (!spawnerDestinations.contains(destination, true)) spawnerDestinations.add(destination);
                } else if (!otherDestinations.contains(destination, true)) {
                    otherDestinations.add(destination);
                }
            }

            // Fallback for the first update after a building is placed.
            if (spawnerDestinations.isEmpty() && otherDestinations.isEmpty()) {
                for (int i = 0; i < 4; i++) {
                    NeoplasmBuilding destination = getNeoplasm(nearby(Mathf.mod(facingRot + i, 4)));
                    if (validBuilding(destination, item) && !otherDestinations.contains(destination, true)) {
                        otherDestinations.add(destination);
                    }
                }
            }

            Seq<NeoplasmBuilding> destinations = spawnerDestinations.isEmpty()
                ? otherDestinations : spawnerDestinations;
            if (destinations.isEmpty()) return;

            NeoplasmBuilding destination = destinations.get(Mathf.random(destinations.size - 1));
            if (!validBuilding(destination, item)) return;

            current = null;
            items.remove(item, 1);
            destination.handleItem(this, item);
            if (items.any()) current = items.first();
        }

        public void coverQueue(Block cordPlacement){
            // Iterate backwards because removal shifts later entries. Discard
            // stale or obstructed requests instead of retaining them forever.
            for(int i = Queue.size - 1; i >= 0; i--){
                Tile queued = Queue.get(i);
                if(queued == null || Vars.world == null
                    || Vars.world.tile(queued.x, queued.y) != queued){
                    Queue.remove(i);
                    continue;
                }

                if(queued.build == null){
                    queued.setBlock(cordPlacement, team, rotation);
                }
                // An existing cord already fulfils the request. A different
                // building blocks it, so this request cannot be fulfilled.
                Queue.remove(i);
            }
        }

        @Override
        public void death() {
            if (current != null) {
                current = null;
                items.clear();
            }
            super.death();
        }

        @Override
        public Building back() {
            int trns = this.block.size / 2 + 1;
            return this.nearby(Geometry.d4(this.facingRot + 2).x * trns, Geometry.d4(this.facingRot + 2).y * trns);
        }

        public Tile backTile() {
            int trns = this.block.size / 2 + 1;
            return this.nearbyXY(Geometry.d4(this.facingRot + 2).x * trns, Geometry.d4(this.facingRot + 2).y * trns);
        }

        @Override
        public Building right() {
            int trns = this.block.size / 2 + 1;
            return this.nearby(Geometry.d4(this.facingRot + 3).x * trns, Geometry.d4(this.facingRot + 3).y * trns);
        }

        @Override
        public Building left() {
            int trns = this.block.size / 2 + 1;
            return this.nearby(Geometry.d4(this.facingRot + 1).x * trns, Geometry.d4(this.facingRot + 1).y * trns);
        }

        @Override
        public Building front() {
            int trns = this.block.size / 2 + 1;
            return this.nearby(Geometry.d4(this.facingRot).x * trns, Geometry.d4(this.facingRot).y * trns);
        }

        @Override
        public void takeBlood() {
            NeoplasmBuilding behind = getNeoplasm(back());
            if (behind != null && liquids.get(blood) < liquidCapacity) {
                moveFromLiquid(behind, blood);
            }

            NeoplasmBuilding left = getNeoplasm(left());
            if (left != null && liquids.get(blood) < liquidCapacity) {
                moveFromLiquid(left, blood);
            }

            NeoplasmBuilding right = getNeoplasm(right());
            if (right != null && liquids.get(blood) < liquidCapacity) {
                moveFromLiquid(right, blood);
            }
        }

        protected void drawAt(float x, float y, int bits, float rotation, Autotiler.SliceMode slice) {
            Draw.z(Layer.blockUnder);

            //drawBeat(xscl, yscl); //TODO SMOOTH TRAILING
            TextureRegion textureRegion = (sliced(Core.atlas.find(name + "-" + bits), slice));
            float color = Draw.getColor().toFloatBits(); // gets current packed RGBA float

            float xs = (xscl > 0) ? xscl + ((beat - 1f) * 1) : xscl - ((beat - 1f) * 1);
            float ys = (yscl > 0) ? yscl + ((beat - 1f) * 1) : yscl - ((beat - 1f) * 1);
            float w = (float) (textureRegion.width) * textureRegion.scl() * xs, h = (float) (textureRegion.height) * textureRegion.scl() * -ys;
            float u = textureRegion.u, u2 = textureRegion.u2;
            float v = textureRegion.v, v2 = textureRegion.v2;
            Draw.color(new Color(1.0F, 1.0F, 1.0F, 1.0F).lerp(beatColor, (beat - 1)));
            float x0 = x - w / 2f;
            float y0 = y - h / 2f;

            boolean flipY = !(facingRot == 1);
            boolean flipX = !(facingRot == 3);

            float stretchFactor = 6f; // or whatever feels right
            float stretchFront = (((front() instanceof NeoplasmBuilding neo) ? (neo.beat - 1) : 0f)) * stretchFactor;
            float stretchBack = (((back() instanceof NeoplasmBuilding neo) ? (neo.beat - 1) : 0f)) * stretchFactor;
            float stretchLeft = (((left() instanceof NeoplasmBuilding neo) ? (neo.beat - 1) : 0f)) * stretchFactor;
            float stretchRight = (((right() instanceof NeoplasmBuilding neo) ? (neo.beat - 1) : 0f)) * stretchFactor;
            float[] vertices = {
                    // bottom-left
                    x0 - ((left() instanceof NeoplasmBuilding && !flipX) ? stretchLeft : (right() instanceof NeoplasmBuilding && flipX) ? stretchRight : 0f),
                    y0 + ((back() instanceof NeoplasmBuilding && !flipY) ? stretchBack : (front() instanceof NeoplasmBuilding && flipY) ? stretchFront : 0f)
                    , color, u, v, 0f,

                    // bottom-right
                    x0 + w + ((left() instanceof NeoplasmBuilding && !flipX) ? stretchLeft : (right() instanceof NeoplasmBuilding && flipX) ? stretchRight : 0f),
                    y0 + ((front() instanceof NeoplasmBuilding && !flipY) ? stretchFront : (back() instanceof NeoplasmBuilding && flipY) ? stretchBack : 0f)
                    , color, u2, v, 0f,

                    // top-right
                    x0 + w + ((right() instanceof NeoplasmBuilding && !flipX) ? stretchRight : (left() instanceof NeoplasmBuilding && flipX) ? stretchLeft : 0f),
                    y0 + h - ((front() instanceof NeoplasmBuilding && !flipY) ? stretchFront : ((back() instanceof NeoplasmBuilding && flipY) ? stretchBack : 0f))
                    , color, u2, v2, 0f,

                    // top-left
                    x0 - ((right() instanceof NeoplasmBuilding && !flipX) ? stretchRight : (left() instanceof NeoplasmBuilding && flipX) ? stretchLeft : 0f),
                    y0 + h - ((back() instanceof NeoplasmBuilding && !flipY) ? stretchBack : (front() instanceof NeoplasmBuilding && flipY) ? stretchFront : 0f)
                    , color, u, v2, 0f
            };
            Draw.vert((sliced(Core.atlas.find(name + "-" + bits), slice)).texture, vertices, 0, vertices.length);
            //Draw.rect(sliced(Core.atlas.find(name + "-" + bits), slice), x, y, rotation);

            // Reset drawing properties
            Draw.color();
            Draw.scl();
        }

        public void onProximityUpdate() {
            super.onProximityUpdate();

            int bit = 0;
            for (int i = 0; i < 8; i++){
                Tile mask = Vars.world.tile(tile.x + Geometry.d8(i).x, tile.y + Geometry.d8(i).y);
                if (mask != null && mask.build instanceof NeoplasmBuilding neoplasmBuilding) {
                    bit |= 1 << (i);
                    neoplasmBuilding.ready = neoplasmBuilding.alreadyBeat = neoplasmBuilding.grow = false;
                    neoplasmBuilding.beatTimer = 0f;
                }
            }

            blendbits = bitmask[bit];
            xscl = 1;
            yscl = 1;
        }

        @Override
        public void write(Writes write) {
            super.write(write);

            write.i(facingRot);
            write.i(task);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);

            facingRot = read.i();
            task = read.i();
        }
    }
}
