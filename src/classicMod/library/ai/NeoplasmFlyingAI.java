package classicMod.library.ai;

import arc.math.Mathf;
import arc.math.Rand;
import mindustry.ai.types.FlyingAI;
import mindustry.gen.Building;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.meta.BlockFlag;

import static mindustry.Vars.*;
import static mindustry.world.meta.BlockFlag.*;

/**
 * FlyingAI with map-wide Neoplasm strategic routing and chokepoint avoidance.
 * Suicide bombers are automatically excluded so they charge straight into targets.
 */
public class NeoplasmFlyingAI extends FlyingAI {
    private final NeoplasmAIController deathMemory = new NeoplasmAIController();
    private static final Rand rand = new Rand();
    private static final BlockFlag[] randomTargets = {core, storage, generator, launchPad, factory, repair, battery, reactor, drill};

    public boolean isIgnoringDeathHeat() {
        return deathMemory.ignoreDeathHeat;
    }

    public void setIgnoreDeathHeat(boolean ignore) {
        deathMemory.ignoreDeathHeat = ignore;
    }

    @Override
    public void updateUnit() {
        deathMemory.unit(unit);
        deathMemory.updateNeoplasmTracking();
        super.updateUnit();
    }

    @Override
    public Teamc findTarget(float x, float y, float range, boolean air, boolean ground) {
        var local = target(x, y, range, air, ground);
        if (local != null) return local;

        return findMainTarget(x, y, range, air, ground);
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
        } else {
            for (var flag : unit.type.targetFlags) {
                if (flag != null && ground) {
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
            // Suicide bombers dive directly onto targets without detouring around defenses.
            if (NeoplasmDeathHeatmap.isSuicideBomber(unit)) {
                moveTo(target, 0f);
                if (unit.type.faceTarget) unit.lookAt(target);
                return;
            }

            Tile destination = null;
            if (target instanceof Building building) {
                destination = building.tile;
            } else if (target instanceof Unit other) {
                destination = other.tileOn();
            }

            float engageRange = unit.range() * 0.82f;

            // Use the map-wide strategic Dijkstra route so flyers proactively pick
            // open flanking paths around fortified chokepoints and turret lines.
            boolean strategicallyRouted = destination != null
                    && !unit.within(destination, engageRange)
                    && deathMemory.applyStrategicTarget(
                            destination, engageRange, PathfinderCustom.costNone
                    );

            if (!strategicallyRouted
                    && (destination == null || !deathMemory.avoidNearbyDanger(destination))) {
                if (unit.type.circleTarget) {
                    circleAttack(unit.type.circleTargetRadius);
                } else {
                    moveTo(target, engageRange);
                }
            }

            if (unit.type.faceTarget) {
                unit.lookAt(target);
            }
        }

        if (target == null && state.rules.waves && unit.team == state.rules.defaultTeam) {
            Tile spawner = getClosestSpawner();
            if (spawner != null) moveTo(spawner, state.rules.dropZoneRadius + 130f);
        }
    }
}
