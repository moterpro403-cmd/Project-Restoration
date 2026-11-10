package classicMod.library.ai;

import arc.math.*;
import arc.math.geom.*;
import mindustry.ai.Pathfinder;
import mindustry.core.World;
import mindustry.entities.Sized;
import mindustry.gen.*;
import mindustry.world.Tile;

import static mindustry.Vars.*;

public class NeoplasmHugAI extends NeoplasmAIController {

    @Override
    public Teamc findMainTarget(float x, float y, float range, boolean air, boolean ground) {
        Building enemyCore = unit.closestEnemyCore();

        // Attack nearby enemies first. When nothing is nearby, keep the enemy
        // core as a long-range strategic destination for the pathfinder.
        Teamc nearby = target(x, y, range, air, ground);
        if (enemyCore != null && Mathf.within(x, y, enemyCore.x, enemyCore.y, range)) {
            return enemyCore;
        }
        return nearby != null ? nearby : enemyCore;
    }

    @Override
    public void updateMovement() {
        unloadPayloads();

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
            if (spawner == null && core == null) move = false;
        }

        if (move && target != null && !invalid(target)) {
            Tile destination = Vars.world.tile(target.tileX(), target.tileY());

            // Reposition away from remembered danger while maintaining the target.
            if (!avoidNearbyDanger(destination)) {
                boolean blocked = World.raycast(unit.tileX(), unit.tileY(), target.tileX(), target.tileY(), (x, y) -> {
                    for (Point2 point : Geometry.d4c) {
                        if (!unit.canPass(x + point.x, y + point.y)) return true;
                    }
                    return false;
                });

                if (blocked) {
                    // Try a local detour first; fall back to the enemy-core field.
                    if (!moveAroundBlockedTarget(destination)) pathfind(Pathfinder.fieldCore);
                } else {
                    float targetSize = target instanceof Sized sized ? sized.hitSize() : 8f;
                    float contactDistance = (unit.hitSize + targetSize) * 0.5f + 2f;
                    float stopDistance = unit.type.hasWeapons()
                            ? Math.max(unit.type.range * 0.78f, contactDistance)
                            : contactDistance;

                    moveTo(target, stopDistance, 55f);
                }
            }
        } else if (move && core != null) {
            pathfind(Pathfinder.fieldCore);
        }

        if (unit.type.canBoost && unit.elevation > 0.001f && !unit.onSolid()) {
            unit.elevation = Mathf.approachDelta(unit.elevation, 0f, unit.type.riseSpeed);
        }

        faceTarget();
    }
}
