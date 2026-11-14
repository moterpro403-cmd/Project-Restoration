package classicMod.library.ai;

import arc.math.Rand;
import arc.util.Time;
import mindustry.content.Liquids;
import mindustry.content.StatusEffects;
import mindustry.entities.Puddles;
import mindustry.gen.Building;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.meta.BlockFlag;

import static mindustry.Vars.state;

public class NeoplasmFlankerAI extends NeoplasmGroundAI {
    public NeoplasmFlankerAI() {
        deathMemory.dangerSensitivity = 0.72f;
    }

    private static final Rand rand = new Rand();
    private static final BlockFlag[] flankPriorities = {
            BlockFlag.generator, BlockFlag.battery, BlockFlag.drill,
            BlockFlag.factory, BlockFlag.repair, BlockFlag.storage, BlockFlag.core
    };
    private float nextShedTime;

    @Override
    public void updateUnit() {
        super.updateUnit();
        if (unit == null || !unit.isValid() || Time.time < nextShedTime) return;
        if (unit.healthf() < 0.55f && unit.damaged()) {
            nextShedTime = Time.time + 18f * 60f;
            Tile tile = unit.tileOn();
            if (tile != null) Puddles.deposit(tile, Liquids.neoplasm, 14f);
            unit.apply(StatusEffects.overdrive, 150f);
        }
    }

    @Override
    public Teamc findMainTarget(float x, float y, float range, boolean air, boolean ground) {
        if (state.rules.randomWaveAI) {
            rand.setSeed(unit.type.id + (state.rules.waves ? state.wave : unit.id));
            for (int attempt = 0; attempt < 4; attempt++) {
                Teamc result = targetFlagActive(x, y,
                        flankPriorities[rand.random(flankPriorities.length - 1)], true);
                if (result != null) return result;
            }
        } else {
            for (BlockFlag flag : flankPriorities) {
                if (ground) {
                    Teamc result = targetFlagActive(x, y, flag, true);
                    if (result != null) return result;
                }
            }
        }
        return targetFlag(x, y, BlockFlag.core, true);
    }

    @Override
    public void updateMovement() {
        if (target != null && unit.hasWeapons()) {
            Tile destination = null;
            if (target instanceof Building building) destination = building.tile;
            else if (target instanceof Unit other) destination = other.tileOn();

            float engageRange = unit.range() * 0.85f;
            if (destination != null && !unit.within(destination, engageRange)) {
                // Preserve the selected backline target; the old fallback sent the
                // flanker toward the core instead of continuing its raid.
                if (!deathMemory.avoidNearbyDanger(destination)) {
                    moveTo(target, engageRange * 0.82f);
                }
            } else {
                moveTo(target, engageRange * 0.72f);
            }
            if (unit.type.faceTarget) unit.lookAt(target);
            return;
        }
        super.updateMovement();
    }
}
