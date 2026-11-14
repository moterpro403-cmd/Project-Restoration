package classicMod.library.ai;

import mindustry.gen.Building;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.meta.BlockFlag;

/** Standoff artillery behavior for Polyctenid. */
public class NeoplasmArtilleryAI extends NeoplasmGroundAI {
    private static final float holdRangeFraction = 0.78f;
    private static final float keepOutFraction = 0.36f;
    private static final BlockFlag[] siegePriorities = {
            BlockFlag.turret, BlockFlag.generator, BlockFlag.factory, BlockFlag.core
    };

    @Override
    public Teamc findMainTarget(float x, float y, float range, boolean air, boolean ground) {
        for (BlockFlag flag : siegePriorities) {
            Teamc candidate = targetFlagActive(x, y, flag, true);
            if (candidate != null) return candidate;
        }
        return targetFlag(x, y, BlockFlag.core, true);
    }

    @Override
    public void updateMovement() {
        if (target == null || !unit.hasWeapons()) {
            super.updateMovement();
            return;
        }

        Tile destination = null;
        if (target instanceof Building building) destination = building.tile;
        else if (target instanceof Unit other) destination = other.tileOn();

        float holdRange = Math.max(unit.hitSize * 2f, unit.range() * holdRangeFraction);
        float keepOutRange = Math.max(unit.hitSize * 2f, unit.range() * keepOutFraction);

        if (destination == null) {
            moveTo(target, holdRange);
        } else if (!unit.within(destination, holdRange)) {
            boolean routed = deathMemory.applyStrategicTarget(
                    destination, holdRange, PathfinderCustom.costLegs
            );
            if (!routed && !deathMemory.avoidNearbyDanger(destination)) {
                moveTo(target, holdRange);
            }
        } else if (unit.within(target, keepOutRange)) {
            vec.set(unit.x - target.getX(), unit.y - target.getY());
            if (vec.isZero()) vec.trns(unit.rotation + 180f, 1f);
            unit.movePref(vec.setLength(unit.speed()));
        }

        unit.lookAt(target);
    }
}
