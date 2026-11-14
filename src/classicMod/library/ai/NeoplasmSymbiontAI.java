package classicMod.library.ai;

import arc.math.Mathf;
import arc.util.Time;
import classicMod.library.blocks.neoplasiaBlocks.NeoplasmCordNetwork;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.entities.Units;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.meta.BlockFlag;

import static mindustry.Vars.state;

/**
 * Low-altitude symbiotic support AI for Mycelid.
 * Escorts nearby Neoplasm assault clusters along strategic routes, grafts
 * temporary protective biomass onto up to 3 allies every 5 seconds, dissolves
 * enemy walls, and bridges CausticCord arteries in safe zones.
 */
public class NeoplasmSymbiontAI extends NeoplasmFlyingAI {
    private static final float escortScanRadius = 150f;
    private static final float graftRadius = 96f;
    private static final float graftPulseTicks = 300f;
    private static final float cordBridgeTicks = 360f;

    private Unit escortLeader;
    private float nextEscortScan = 0f;
    private float nextGraftTime = 0f;
    private float nextCordAttempt = 0f;
    private int graftedCount;

    @Override
    public void updateUnit() {
        super.updateUnit();
        if (unit == null || !unit.isValid() || Vars.net.client()) return;

        float now = Time.time;
        if (now >= nextEscortScan) {
            nextEscortScan = now + 35f + Mathf.random(15f);
            refreshEscortLeader();
        }

        if (now >= nextGraftTime) {
            nextGraftTime = now + graftPulseTicks;
            pulseTissueGraft();
        }

        if (now >= nextCordAttempt) {
            nextCordAttempt = now + cordBridgeTicks;
            tryBridgeAdjacentCord();
        }
    }

    private void refreshEscortLeader() {
        escortLeader = null;
        final float[] bestHealth = {0f};
        Units.nearby(unit.team, unit.x, unit.y, escortScanRadius, other -> {
            if (other != unit && other.isValid() && other.type != unit.type
                    && NeoplasmDeathHeatmap.isNeoplasm(other)
                    && other.maxHealth > bestHealth[0]) {
                bestHealth[0] = other.maxHealth;
                escortLeader = other;
            }
        });
    }

    private void pulseTissueGraft() {
        graftedCount = 0;
        Units.nearby(unit.team, unit.x, unit.y, graftRadius, ally -> {
            if (graftedCount >= 3 || ally == unit || !ally.isValid()) return;
            if (NeoplasmDeathHeatmap.isNeoplasm(ally)) {
                ally.apply(StatusEffects.shielded, 180f);
                graftedCount++;
            }
        });
    }

    private void tryBridgeAdjacentCord() {
        Tile tile = unit.tileOn();
        if (tile == null) return;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                Tile check = Vars.world.tile(tile.x + dx, tile.y + dy);
                if (check != null && check.build != null && check.build.team == unit.team
                        && NeoplasmCordNetwork.isVascularBlock(check.build)
                        && NeoplasmCordNetwork.isConnected(check.build)) {
                    deathMemory.trySeedCordOrHeart(false);
                    return;
                }
            }
        }
    }

    @Override
    public Teamc findMainTarget(float x, float y, float range, boolean air, boolean ground) {
        Teamc wall = Units.findEnemyTile(unit.team, x, y, range * 1.15f,
                b -> b.block instanceof Wall);
        if (wall != null) return wall;

        Teamc turret = targetFlag(x, y, BlockFlag.turret, true);
        if (turret != null) return turret;

        return super.findMainTarget(x, y, range, air, ground);
    }

    @Override
    public void updateMovement() {
        if (escortLeader != null && escortLeader.isValid()) {
            Tile leaderTile = escortLeader.tileOn();
            if (leaderTile != null && !unit.within(escortLeader, 52f)) {
                if (!deathMemory.applyStrategicTarget(leaderTile, 44f, PathfinderCustom.costNone)) {
                    moveTo(escortLeader, 44f);
                }
            } else {
                circleAttack(56f);
            }
            if (target != null && unit.type.faceTarget) {
                unit.lookAt(target);
            }
            return;
        }

        if (target == null && state.rules.waves && unit.team == state.rules.defaultTeam) {
            Tile spawner = getClosestSpawner();
            if (spawner != null) moveTo(spawner, state.rules.dropZoneRadius + 130f);
            return;
        }

        super.updateMovement();
    }
}