package classicMod.library.ai;

import mindustry.ai.types.FlyingAI;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.world.Tile;

/**
 * Original FlyingAI behavior with Neoplasm death-memory avoidance layered on top.
 */
public class NeoplasmFlyingAI extends FlyingAI {
    private final NeoplasmAIController deathMemory = new NeoplasmAIController();

    @Override
    public void updateUnit() {
        deathMemory.unit(unit);
        deathMemory.updateNeoplasmTracking();
        super.updateUnit();
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
