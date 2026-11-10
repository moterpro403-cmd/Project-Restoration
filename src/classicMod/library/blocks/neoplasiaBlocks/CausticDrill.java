package classicMod.library.blocks.neoplasiaBlocks;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.math.Mathf;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import classicMod.content.RVars;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.entities.Effect;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Tile;

import static classicMod.content.RVars.CordCanDrill;

public class CausticDrill extends NeoplasmBlock {

    protected final ObjectIntMap<Item> oreCount = new ObjectIntMap<>();
    protected final Seq<Item> itemArray = new Seq<>();

    /** Vanilla-style drilling values: work is measured in frames. */
    public float drillTime = 300f;
    public float hardnessDrillMultiplier = 50f;
    public float warmupSpeed = 0.015f;
    public float liquidBoostIntensity = 1.6f;
    public float rotateSpeed = 2f;
    public float drillEffectChance = 0.02f;
    public float updateEffectChance = 0.02f;
    public int tier;

    public Effect drillEffect = Fx.mineHuge;
    public Effect updateEffect = Fx.pulverizeSmall;

    @Nullable
    protected Item returnItem;
    protected int returnCount;

    public CausticDrill(String name) {
        super(name);
        hasItems = true;
        unloadable = true;
        tier = Items.tungsten.hardness;
    }

    private boolean canMineWallDrop(Item item) {
        return item != null && item.hardness <= tier && CordCanDrill.contains(item);
    }

    /**
     * Returns the actual ore represented by a tile.
     * Floor deposits are mineable when the tile has no placed block;
     * wall deposits are exposed through Tile.wallDrop().
     */
    private Item mineableDrop(Tile tile) {
        if (tile == null) return null;

        Item wall = tile.wallDrop();
        if (canMineWallDrop(wall)) return wall;

        if (tile.block() == Blocks.air || tile.block() instanceof CausticCord) {
            Item floor = tile.drop();
            if (canMineWallDrop(floor)) return floor;
        }

        return null;
    }

    /**
     * Like Mindustry's Drill, only mine ore tiles inside the drill footprint.
     * This Neoplasm variant also supports the ore-bearing wall faces that its
     * original placement rules allowed.
     */
    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation) {
        countOre(tile, rotation);
        return returnItem != null;
    }

    public boolean canMine(Tile tile) {
        return mineableDrop(tile) != null;
    }

    public Item getDrop(Tile tile) {
        return mineableDrop(tile);
    }

    protected void countOre(Tile tile, int drillRotation) {
        returnItem = null;
        returnCount = 0;
        oreCount.clear();
        itemArray.clear();
        if (tile == null) return;

        // Count every valid floor or wall deposit covered by the drill footprint.
        for (Tile other : tile.getLinkedTilesAs(this, tempTiles)) {
            Item drop = getDrop(other);
            if (drop != null) oreCount.increment(drop, 0, 1);
        }

        // Wall-ore deposits can also be mined from the four sides of the drill.
        for (int r = 0; r < 4; r++) {
            for (int i = 0; i < size; i++) {
                nearbySide(tile.x, tile.y, Mathf.mod(drillRotation + r, 4), i, Tmp.p1);
                Tile other = Vars.world.tile(Tmp.p1.x, Tmp.p1.y);
                if (other != null) {
                    Item drop = other.wallDrop();
                    if (canMineWallDrop(drop)) oreCount.increment(drop, 0, 1);
                }
            }
        }

        for (Item item : oreCount.keys()) {
            itemArray.add(item);
        }

        // Match the vanilla Drill's dominant-ore ordering.
        itemArray.sort((item1, item2) -> {
            int type = Boolean.compare(!item1.lowPriority, !item2.lowPriority);
            if (type != 0) return type;
            int amounts = Integer.compare(oreCount.get(item1, 0), oreCount.get(item2, 0));
            if (amounts != 0) return amounts;
            return Integer.compare(item1.id, item2.id);
        });

        if (itemArray.size > 0) {
            returnItem = itemArray.peek();
            returnCount = oreCount.get(returnItem, 0);
        }
    }

    public float getDrillTime(Item item) {
        return drillTime + hardnessDrillMultiplier * item.hardness;
    }

    public class CausticDrillBuild extends NeoplasmBuilding {
        public float progress;
        public float warmup;
        public float timeDrilled;
        public float lastDrillSpeed;
        public int dominantItems;
        public Item dominantItem;

        @Override
        public boolean acceptItem(mindustry.gen.Building source, Item item) {
            // The extractor is an output node; resources must travel outward
            // through the Cord network, never back into the drill.
            return false;
        }

        @Override
        public boolean shouldAmbientSound() {
            return efficiency > 0.01f && items.total() < itemCapacity && dominantItem != null;
        }

        @Override
        public void onProximityUpdate() {
            super.onProximityUpdate();
            Item previous = dominantItem;
            CausticDrill.this.countOre(tile, rotation);
            dominantItem = CausticDrill.this.returnItem;
            dominantItems = CausticDrill.this.returnCount;
            if (previous != dominantItem) progress = 0f;
        }

        @Override
        public void update() {
            super.update();

            if (timer(timerDump, dumpTime)) {
                dump(dominantItem != null && items.has(dominantItem) ? dominantItem : null);
            }

            if (dominantItem == null || dominantItems <= 0) {
                lastDrillSpeed = 0f;
                warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
                return;
            }

            timeDrilled += warmup * delta();
            float delay = getDrillTime(dominantItem);

            if (items.total() < itemCapacity && efficiency > 0f && enabled) {
                float speed = Mathf.lerp(1f, liquidBoostIntensity, optionalEfficiency) * efficiency;
                lastDrillSpeed = speed * dominantItems * warmup / delay;
                warmup = Mathf.approachDelta(warmup, speed, warmupSpeed);
                progress += delta() * dominantItems * speed * warmup;

                if (Mathf.chanceDelta(updateEffectChance * warmup)) {
                    updateEffect.at(x + Mathf.range(size * 2f), y + Mathf.range(size * 2f));
                }
            } else {
                lastDrillSpeed = 0f;
                warmup = Mathf.approachDelta(warmup, 0f, warmupSpeed);
                return;
            }

            if (progress >= delay && items.total() < itemCapacity) {
                int amount = (int)(progress / delay);
                for (int i = 0; i < amount && items.total() < itemCapacity; i++) {
                    // Uses Building.offload(), the same output path as vanilla Drill.
                    offload(dominantItem);
                }
                progress %= delay;

                if (wasVisible && Mathf.chanceDelta(drillEffectChance * warmup)) {
                    drillEffect.at(x, y, dominantItem.color);
                }
            }
        }

        @Override
        public float progress() {
            return dominantItem == null ? 0f : Mathf.clamp(progress / getDrillTime(dominantItem));
        }

        @Override
        public void drawCracks() {
            // Organic blocks do not use the standard block-crack overlay.
        }

        @Override
        public void draw() {
            drawBeat(1f, 1f, 0.25f);
            Draw.rect(Core.atlas.find(name), x, y);
            Draw.color();
            Draw.reset();
        }

        @Override
        public byte version() {
            return 1;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.i(dominantItem != null ? dominantItem.id : -1);
            write.f(progress);
            write.f(warmup);
            write.f(timeDrilled);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            int id = read.i();
            dominantItem = id < 0 ? null : Vars.content.item(id);
            if (revision >= 1) {
                progress = read.f();
                warmup = read.f();
                timeDrilled = read.f();
            }
        }
    }
}
