package classicMod.library.ai;

import arc.math.Mathf;
import arc.math.Rand;
import mindustry.ai.types.FlyingAI;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.gen.Teamc;
import mindustry.world.meta.BlockFlag;

import static mindustry.Vars.*;
import static mindustry.world.meta.BlockFlag.*;
import mindustry.world.Tile;

/**
 * Original FlyingAI behavior with Neoplasm death-memory avoidance layered on top.
 */
public class NeoplasmFlyingAI extends FlyingAI {
    private final NeoplasmAIController deathMemory = new NeoplasmAIController();
    final static Rand rand = new Rand();
    final static BlockFlag[] randomTargets = {core, storage, generator, launchPad, factory, repair, battery, reactor, drill};

    @Override
    public void updateUnit() {
        deathMemory.unit(unit);
        deathMemory.updateNeoplasmTracking();
        super.updateUnit();
    }

    @Override
    public Teamc findTarget(float x, float y, float range, boolean air, boolean ground) {
        var result = findMainTarget(x, y, range, air, ground);
        return checkTarget(result, x, y, range) ? target(x, y, range, air, ground) : result;
    }

    @Override
    public Teamc findMainTarget(float x, float y, float range, boolean air, boolean ground) {
        var enemyCore = targetFlag(x, y, BlockFlag.core, true);
        if (enemyCore != null && Mathf.within(x, y, enemyCore.getX(), enemyCore.getY(), range)) {
            return enemyCore;
        }

        if (state.rules.randomWaveAI) {
            rand.setSeed(unit.type.id + (state.rules.waves ? state.wave : unit.id));
            for (int attempt = 0; attempt < 5; attempt++) {
                Teamc result = targetFlagActive(x, y, randomTargets[rand.random(randomTargets.length - 1)], true);
                if (result != null) return result;
            }
            Teamc result = target(x, y, range, air, ground);
            if (result != null) return result;
        } else {
            for (var flag : unit.type.targetFlags) {
                if (flag == null) {
                    Teamc result = target(x, y, range, air, ground);
                    if (result != null) return result;
                } else if (ground) {
                    Teamc result = targetFlagActive(x, y, flag, true);
                    if (result != null) return result;
                }
            }
        }
        return enemyCore;
    }

    @Override
    public void updateMovement() {
        unloadPayloads();

        if (target != null && unit.hasWeapons()) {
            Tile destination = null;
            if (target instanceof Building building) {
                destination = building.tile;
            } else if (target instanceof Unit other) {
                destination = other.tileOn();
            }

            // Keep FlyingAI targeting and firing, but sidestep remembered deaths.
            if (destination == null || !deathMemory.avoidNearbyDanger(destination)) {
                if (unit.type.circleTarget) {
                    circleAttack(unit.type.circleTargetRadius);
                } else {
                    moveTo(target, unit.type.range * 0.8f);
                }
            }

            unit.lookAt(target);
        }

        if (target == null && state.rules.waves && unit.team == state.rules.defaultTeam) {
            Tile spawner = getClosestSpawner();
            if (spawner != null) moveTo(spawner, state.rules.dropZoneRadius + 130f);
        }
    }
}
