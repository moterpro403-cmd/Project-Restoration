package classicMod.library.ai;

import mindustry.ai.types.GroundAI;

/**
 * GroundAI remains the real superclass, preserving its native stuck handling,
 * targeting, and movement. Only its pathfind step is intercepted for evasion.
 */
public class NeoplasmGroundAI extends GroundAI {
    private final NeoplasmAIController deathMemory = new NeoplasmAIController();

    @Override
    public void updateUnit() {
        deathMemory.unit(unit);
        deathMemory.updateNeoplasmTracking();
        super.updateUnit();
    }

    @Override
    public void pathfind(int pathTarget, boolean stopAtTargetTile, boolean avoidance) {
        deathMemory.unit(unit);
        if (!deathMemory.applyDodgePathfind(pathTarget)) {
            super.pathfind(pathTarget, stopAtTargetTile, avoidance);
        }
    }
}
