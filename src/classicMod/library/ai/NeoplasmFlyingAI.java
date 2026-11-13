package classicMod.library.ai;

import arc.math.Mathf;
import arc.math.Rand;
import mindustry.ai.types.FlyingAI;
import classicMod.library.ai.PathfinderCustom;
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
        Teamc result = findMainTarget(x, y, range, air, ground);

        // Main-target selection may deliberately return a distant enemy core or
        // flagged building as a strategic travel destination. The stock
        // checkTarget() marks anything outside weapon range invalid; replacing
        // that result with a local search can return null and leave flyers idle.
        // Still prioritize an enemy that is already within weapon range.
        if (result == null) return target(x, y, range, air, ground);
        if (checkTarget(result, x, y, range)) {
            Teamc nearby = target(x, y, range, air, ground);
            return nearby != null ? nearby : result;
        }
        return result;
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

            // When approaching buildings, use the shared strategic route if
            // defenses make the direct approach substantially more dangerous.
            boolean strategicallyRouted = destination != null
                && !unit.within(destination, unit.type.range * 0.8f)
                && deathMemory.applyStrategicTarget(
                    destination, unit.type.range * 0.8f, PathfinderCustom.costNone
                );

            // Keep FlyingAI targeting and firing when no strategic detour is needed.
            if (!strategicallyRouted
                && (destination == null || !deathMemory.avoidNearbyDanger(destination))) {
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
