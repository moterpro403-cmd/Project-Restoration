package classicMod.library.ai;

import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import mindustry.ai.Pathfinder;
import mindustry.ai.types.HugAI;
import mindustry.core.World;
import mindustry.entities.Sized;
import mindustry.gen.Building;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.world.Tile;

import static mindustry.Vars.*;

public class NeoplasmHugAI extends HugAI {
    private final NeoplasmAIController deathMemory = new NeoplasmAIController();

    @Override
    public void updateUnit() {
        deathMemory.unit(unit);
        deathMemory.updateNeoplasmTracking();
        super.updateUnit();
    }

    @Override
    public void pathfind(int pathTarget) {
        deathMemory.unit(unit);
        if (!deathMemory.applyDodgePathfind(pathTarget)) {
            super.pathfind(pathTarget);
        }
    }

    @Override
    public Teamc findMainTarget(float x, float y, float range, boolean air, boolean ground) {
        Building enemyCore = unit.closestEnemyCore();
        Teamc nearby = target(x, y, range, air, ground);
        if (enemyCore != null && Mathf.within(x, y, enemyCore.x, enemyCore.y, range)) {
            return enemyCore;
        }
        return nearby != null ? nearby : enemyCore;
    }

    @Override
    public void updateMovement() {
        Building core = unit.closestEnemyCore();

        if (core != null && unit.within(core, unit.range() / 1.1f + core.block.size * tilesize / 2f)) {
            target = core;
            for (var mount : unit.mounts) {
                if (mount.weapon.controllable && mount.weapon.bullet.collidesGround) {
                    mount.target = core;
                }
            }
        }

        boolean move = true;
        if (state.rules.waves && unit.team == state.rules.defaultTeam) {
            Tile spawner = getClosestSpawner();
            if (spawner != null && unit.within(spawner, state.rules.dropZoneRadius + 120f)) move = false;
        }

        // Preserve HugAI's original corner-aware raycast.
        boolean clearShot = target != null && unit.within(target, unit.type.range)
            && !World.raycast(unit.tileX(), unit.tileY(), target.tileX(), target.tileY(), (x, y) -> {
                for (Point2 p : Geometry.d4c) {
                    if (!unit.canPass(x + p.x, y + p.y)) return true;
                }
                return false;
            });

        if (clearShot) {
            Tile destination = null;
            if (target instanceof Building building) {
                destination = building.tile;
            } else if (target instanceof Unit other) {
                destination = other.tileOn();
            } else {
                destination = world.tile(target.tileX(), target.tileY());
            }

            float strategicStop = Math.max(unit.hitSize * 2f, unit.range() * 0.8f);
            boolean strategicallyRouted = destination != null
                    && deathMemory.applyStrategicTarget(
                            destination, strategicStop, unit.type.flowfieldPathType
                    );

            if (!strategicallyRouted
                    && (destination == null || !deathMemory.avoidNearbyDanger(destination))) {
                if (unit.within(target, (unit.hitSize + (target instanceof Sized sized ? sized.hitSize() : 1f)) * 0.5f)) {
                    unit.movePref(vec.set(target).sub(unit).rotate(90f).setLength(unit.speed()));
                } else {
                    unit.movePref(vec.set(target).sub(unit).limit(unit.speed()));
                }
            }
        } else if (move) {
            pathfind(Pathfinder.fieldCore);
        }

        if (unit.type.canBoost && unit.elevation > 0.001f && !unit.onSolid()) {
            unit.elevation = arc.math.Mathf.approachDelta(unit.elevation, 0f, unit.type.descentSpeed);
        }

        faceTarget();
    }
}
