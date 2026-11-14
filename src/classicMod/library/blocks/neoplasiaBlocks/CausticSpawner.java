package classicMod.library.blocks.neoplasiaBlocks;

import arc.Core;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.Mathf;
import mindustry.Vars;
import mindustry.entities.*;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.gen.Building;
import mindustry.world.Tile;
import mindustry.world.consumers.ConsumeItems;

public class CausticSpawner extends NeoplasmBlock {

    public TextureRegion topRegion, ballRegion;
    public float sclOffset = 0f;
    public float spawnTime = 60f;
    /** Self destructs upon a new unit spawn.**/
    public boolean selfDestruct;
    public Effect spawnEffect;
    public Color spawnColor;

    public UnitType spawn;

    public CausticSpawner(String name) {
        super(name);
        itemCapacity = 20;
    }

    @Override
    public void load() {
        super.load();
        topRegion = Core.atlas.find(name + "-top");
        ballRegion = Core.atlas.find(name + "-ball");
    }

    @Override
    protected TextureRegion[] icons() {
        if (Core.atlas.find(name + "-top").found()) return new TextureRegion[]{Core.atlas.find(name), Core.atlas.find(name + "-top")};
        return new TextureRegion[]{Core.atlas.find(name)};
    }

    public class CausticSpawnerBuild extends NeoplasmBuilding {
        public float progress, speedScl;

        public float fraction(){
            return spawnTime <= 0f ? 0f : progress / spawnTime;
        }

        @Override
        public boolean deathImminent() {
            // A spawner must not expire merely because its cord pulse is interrupted.
            return false;
        }

        @Override
        public void draw() {
            float frac = fraction();
            drawBeat(1 + sclOffset, 1 + sclOffset, 0.25f);
            Draw.rect(region, x, y);

            drawBeat(Math.min(frac, 1f + sclOffset), Math.min(frac, 1f + sclOffset), 0.25f);
            if (ballRegion.found()) Draw.rect(ballRegion, x, y);

            drawBeat(1 + sclOffset, 1 + sclOffset, 0.25f);
            if (topRegion.found()) Draw.rect(topRegion, x, y);
        }

        @Override
        public boolean acceptItem(Building source, Item item){
            if (item == null || items.total() >= itemCapacity) return false;
            for (var consume : consumeBuilder){
                if (consume instanceof ConsumeItems consumeItems){
                    for (var stack : consumeItems.items){
                        if (stack.item == item && items.get(item) < stack.amount) return true;
                    }
                }
            }
            return false;
        }

        public void updateEfficiency(){
            boolean hasItemConsumer = false;
            boolean allItemsPresent = true;
            for (var consume : consumeBuilder){
                if (consume instanceof ConsumeItems consumeItems){
                    hasItemConsumer = true;
                    for (var itemStack : consumeItems.items){
                        if (items.get(itemStack.item) < itemStack.amount){
                            allItemsPresent = false;
                            break;
                        }
                    }
                }
                if (!allItemsPresent) break;
            }
            efficiency = hasItemConsumer && allItemsPresent ? 1f : 0f;
        }

        private void spawnUnit(){
            if (Vars.net.client() || spawn == null || !isGrown()
                || efficiency <= 0f || !NeoplasmPopulation.canSpawn(team, spawn)) return;

            var unit = spawn.create(team);
            if (unit == null) return;

            consume();
            progress = 0f;
            speedScl = 0f;
            if (spawnEffect != null){
                spawnEffect.at(x, y, spawnColor != null ? spawnColor : Color.white);
            }
            unit.set(this);
            unit.rotation(90f);
            unit.add();

            if (selfDestruct){
                var dTile = proximityTiles.copy().removeAll(t ->
                    t == null || !(t.build instanceof CausticCord.CordBuild)
                );
                if (!dTile.isEmpty()){
                    Tile ontile = dTile.get(Mathf.random(dTile.size - 1));
                    if (ontile != null && ontile.build instanceof CausticCord.CordBuild cordBuild){
                        for (int dy = -size; dy < size; dy++){
                            for (int dx = -size; dx < size; dx++){
                                Tile tile = Vars.world.tile(ontile.x + dx, ontile.y + dy);
                                if (tile != null && tile.build == this && !cordBuild.Queue.contains(tile)){
                                    cordBuild.Queue.add(tile);
                                }
                            }
                        }
                    }
                }
                if (tile != null && tile.build == this) tile.setBlock(pipe, team);
            }
        }

        @Override
        public void update() {
            super.update();
            updateEfficiency();
            if (!isGrown()) return;

            if (efficiency > 0f){
                // edelta() already accounts for required-item efficiency.
                progress = Math.min(spawnTime, progress + edelta());
                speedScl = Mathf.lerpDelta(speedScl, 1f, 0.05f);
            } else {
                speedScl = Mathf.lerpDelta(speedScl, 0f, 0.05f);
            }

            // Production is independent of the Neoplasm heartbeat network.
            if (progress >= spawnTime) spawnUnit();
        }

        @Override
        public void updateBeat() {
            super.updateBeat();
            growCord(pipe);
        }
    }
}
