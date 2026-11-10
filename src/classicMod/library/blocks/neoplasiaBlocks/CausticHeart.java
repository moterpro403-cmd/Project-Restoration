package classicMod.library.blocks.neoplasiaBlocks;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.struct.*;
import arc.util.Nullable;
import classicMod.content.RUnitTypes;
import classicMod.content.RBlocks;
import classicMod.library.ai.PathfinderExtended;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.meta.*;
import mindustry.graphics.Pal;

public class CausticHeart extends NeoplasmBlock {

    public float minEfficiency;
    public Attribute attribute;
    public int unitCap;
    public CausticHeart(String name) {
        super(name);
        attribute = Attribute.heat;
        hasItems = true;

        liquidCapacity = 1000f;
        priority = 2.0F;
        flags = EnumSet.of(BlockFlag.core);
        unitCapModifier = 10;
        itemCapacity = 10;
        unitCap = 50;
    }

    @Override
    public void setStats() {
        super.setStats();
        stats.add(Stat.output, "Immitis Stalker, Kalyx, Hydroclast, Rift Squid, Pallium");
        stats.add(Stat.productionTime, 45f, StatUnit.seconds);
    }

    public boolean canPlaceOn(Tile tile, Team team, int rotation) {
        return sumAttribute(attribute, tile.x, tile.y) > minEfficiency;
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        super.drawPlace(x, y, rotation, valid);
        float efficiency = sumAttribute(attribute, x, y);
        boolean canPlace = efficiency > minEfficiency;
        drawPlaceText(Core.bundle.format("bar.efficiency", (int)(efficiency * 100f)), x, y, canPlace);
    }

    @Override
    public void setBars() {
        super.setBars();
        addBar("progress", (HeartBuilding entity) -> new Bar(
                () -> Core.bundle.format("bar.neoplasm-spread",
                        (int)(NeoplasmBlock.spreadCredits(entity.team) * 100f),
                        (int)(NeoplasmBlock.spreadCapacity(entity.team) * 100f)),
                () -> Pal.accent,
                () -> NeoplasmBlock.spreadCredits(entity.team) / Math.max(1f, NeoplasmBlock.spreadCapacity(entity.team))
        ));
        addBar("units", (HeartBuilding entity) -> new Bar(
                () -> Core.bundle.format("bar.unitcap", "Neoplasm", entity.countWaveAttackers(), unitCap),
                () -> Pal.power,
                () -> Mathf.clamp((float)entity.countWaveAttackers() / Math.max(1, unitCap))
        ));
    }

    public class HeartBuilding extends NeoplasmBuilding {

        public boolean inDanger;
        public UnitType unitType = RUnitTypes.blob;
        public @Nullable Unit unit;

        @Nullable
        public Tile getClosestVent() {
            Seq<Tile> avaliableVents = PathfinderExtended.SteamVents.copy().removeAll(tile -> tile.build instanceof CausticHeart.HeartBuilding);
            Tile vent = Geometry.findClosest(this.x, this.y, avaliableVents);
            return (vent != null && !(vent.build instanceof CausticHeart.HeartBuilding)) ? vent : null;
        }

        public boolean canCreate(Team team, UnitType type) {
            return team.data().countType(type) < unitCap && !type.isBanned();
        }

        // Attack waves are separate from the Heart's original infection/blob behavior.
        private float attackWaveTimer;
        private int attackWave;
        private final Seq<UnitType> pendingAttackers = new Seq<>();

        private boolean isWaveAttacker(UnitType type) {
            return type == RUnitTypes.miserable
                    || type == RUnitTypes.kalyx
                    || type == RUnitTypes.hydroBomber
                    || type == RUnitTypes.squid
                    || type == RUnitTypes.pallium;
        }

        private int countWaveAttackers() {
            int count = pendingAttackers.size;
            for (Unit other : Groups.unit) {
                if (!other.dead && other.team == Team.neoplastic && isWaveAttacker(other.type)) count++;
            }
            return count;
        }

        private UnitType waveUnit(int wave, int index) {
            if (wave <= 2) return RUnitTypes.miserable;
            if (wave <= 4) return index % 3 == 0 ? RUnitTypes.kalyx : RUnitTypes.miserable;
            if (wave <= 6) {
                return switch (index % 3) {
                    case 0 -> RUnitTypes.hydroBomber;
                    case 1 -> RUnitTypes.squid;
                    default -> RUnitTypes.kalyx;
                };
            }
            // Tier-weighted selection: the heavy Pallium is deliberately rare,
            // while the bomber is the common late-wave attacker.
            int pick = Math.floorMod(attackWave * 7 + index * 3, 12);
            if (pick == 0) return RUnitTypes.pallium;
            if (pick <= 5) return RUnitTypes.hydroBomber;
            if (pick <= 8) return RUnitTypes.squid;
            return RUnitTypes.kalyx;
        }

        private Tile findAttackSpawnTile(boolean flying) {
            int firstRadius = Math.max(2, block.size / 2 + 1);
            for (int radius = firstRadius; radius <= firstRadius + 6; radius++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
                        Tile candidate = Vars.world.tile(tile.x + dx, tile.y + dy);
                        if (candidate == null || candidate.block() != Blocks.air) continue;
                        if (!flying && candidate.floor() != null && candidate.floor().isLiquid) continue;
                        return candidate;
                    }
                }
            }
            return null;
        }

        private void updateAttackWaves() {
            if (Vars.net.client() || team != Team.neoplastic || team == Vars.state.rules.defaultTeam) return;

            attackWaveTimer += delta();
            float interval = attackWave == 0 ? 60f * 15f : 60f * 45f;
            if (attackWaveTimer < interval) return;

            attackWaveTimer = 0f;
            attackWave++;

            int remaining = Math.max(0, unitCap - countWaveAttackers());
            int amount = Math.min(2 + attackWave, 8);
            amount = Math.min(amount, remaining);

            for (int i = 0; i < amount; i++) {
                UnitType type = waveUnit(attackWave, i);
                if (type != null) pendingAttackers.add(type);
            }
        }

        private void spawnPendingAttackers() {
            if (pendingAttackers.isEmpty() || Vars.net.client()) return;

            UnitType type = pendingAttackers.first();
            if (type == null) {
                pendingAttackers.remove(0);
                return;
            }
            if (!Units.canCreate(Team.neoplastic, type)) return;

            // Keep the assault-wave queue, but spawn units directly onto a valid
            // nearby tile. The optional Birthing Pod structure is no longer used.
            Tile spawnTile = findAttackSpawnTile(type.flying);
            if (spawnTile == null || !NeoplasmBlock.trySpawn(Team.neoplastic)) return;

            Unit attacker = type.create(Team.neoplastic);
            attacker.set(spawnTile.worldx(), spawnTile.worldy());
            attacker.rotation(90f);
            attacker.add();

            Fx.neoplasiaSmoke.at(spawnTile.worldx(), spawnTile.worldy());
            pendingAttackers.remove(0);
        }

        @Override
        public void updateBeat() {

            if (Mathf.chance(0.25) && getClosestVent() != null){
                if (!Vars.net.client() && canCreate(team, unitType) && NeoplasmBlock.trySpawn(team)) {
                    unit = unitType.create(team);
                    unit.set(this);
                    unit.rotation(90f);
                    unit.add();
                    //unit.vel.y = launchVelocity;
                    for (int i = 0; i < 5; i++) {
                        Fx.neoplasiaSmoke.at(this.x + Mathf.random(1), this.y + Mathf.random(1));
                    }
                    //Effect.shake(4f*1.5f, 5f, this);
                    //units.add(unit);
                }
            }

            super.updateBeat();
        }

        @Override
        public void update() {
            if (liquids.get(blood) < liquidCapacity) liquids.add(blood, Math.min(liquidCapacity - liquids.get(blood), liquidCapacity));
            super.update();
            inDanger = (health < maxHealth);
            if (grown){
                if (source) {
                    priority = 0;
                    beatTimer += delta();
                    if (beatTimer >= 25 || (beatTimer >= 15 && inDanger)) {
                        beat = 1.5f;
                        beatTimer = 0;
                        updateBeat();
                        growCord(pipe);
                    }
                    updateAttackWaves();
                    spawnPendingAttackers();
                }
            }
        }

        @Override
        public boolean acceptItem(Building source, Item item) {
            return true;
        }

        @Override
        public void draw() {
            drawBeat(1f, 1f, 0.25f);
            Draw.rect(Core.atlas.find(name), x, y);
            Draw.color();

            Draw.reset();
        }
    }
}
