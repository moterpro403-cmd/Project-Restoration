package classicMod.library.ai;

import arc.math.*;
import mindustry.gen.*;
import mindustry.world.Tile;
import mindustry.world.meta.*;

import static mindustry.Vars.*;
import static mindustry.world.meta.BlockFlag.*;

public class NeoplasmFlyingAI extends NeoplasmAIController {
    final static Rand rand = new Rand();
    final static BlockFlag[] randomTargets = {core, storage, generator, launchPad, factory, repair, battery, reactor, drill};

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

            // Evade remembered danger first, but keep aiming at the enemy so
            // the unit can continue firing while it repositions.
            if (!avoidNearbyDanger(destination)) {
                if (unit.type.circleTarget) {
                    // Use the engine's orbiting attack routine. The former
                    // custom implementation called routeAir() afterward,
                    // which immediately replaced the orbit movement.
                    circleAttack(unit.type.circleTargetRadius);
                } else {
                    moveTo(target, unit.type.range * 0.8f);
                }
            }

            unit.lookAt(target);
        } else if (target == null && state.rules.waves && unit.team == state.rules.defaultTeam) {
            Tile spawner = getClosestSpawner();
            if (spawner != null) moveTo(spawner, state.rules.dropZoneRadius + 130f);
        }
    }

    @Override
    public Teamc findTarget(float x, float y, float range, boolean air, boolean ground) {
        var result = findMainTarget(x, y, range, air, ground);

        // Keep a strategic core target when useful; otherwise fall back to
        // any valid enemy unit/building within this weapon's actual range.
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
}
