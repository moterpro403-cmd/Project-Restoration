package classicMod.library.ai;

import arc.math.Mathf;
import arc.struct.Seq;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.util.Time;
import classicMod.content.RBlocks;
import classicMod.library.blocks.neoplasiaBlocks.NeoplasmCordNetwork;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.StatusEffects;
import mindustry.entities.Units;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.meta.BlockFlag;

import static mindustry.Vars.state;

public class NeoplasmSymbiontAI extends NeoplasmFlyingAI {
    private static final float escortScanRadius = 150f;
    private static final float graftRadius = 96f;
    private static final float graftPulseTicks = 300f;
    private static final float cordBridgeTicks = 360f;

    private Unit escortLeader;
    private float nextEscortScan, nextGraftTime, nextCordAttempt;
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
                    && NeoplasmDeathHeatmap.isNeoplasm(other) && other.maxHealth > bestHealth[0]) {
                bestHealth[0] = other.maxHealth;
                escortLeader = other;
            }
        });
    }

    private void pulseTissueGraft() {
        // Heal the three most damaged nearby Neoplasm allies first.
        Seq<Unit> allies = new Seq<>();
        Units.nearby(unit.team, unit.x, unit.y, graftRadius, ally -> {
            if (ally != unit && ally.isValid() && NeoplasmDeathHeatmap.isNeoplasm(ally)) {
                allies.add(ally);
            }
        });
        allies.sort(ally -> ally.healthf());
        graftedCount = Math.min(3, allies.size);
        for (int i = 0; i < graftedCount; i++) {
            allies.get(i).apply(StatusEffects.shielded, 180f);
        }
    }

    /** Extends a connected artery by one safe tile, at most once per six seconds per unit. */
    private void tryBridgeAdjacentCord() {
        Tile center = unit.tileOn();
        if (center == null || RBlocks.cord == null) return;
        Tile best = null;
        float bestScore = Float.MAX_VALUE;

        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                Tile source = Vars.world.tile(center.x + dx, center.y + dy);
                if (source == null || source.build == null || source.build.team != unit.team
                        || !NeoplasmCordNetwork.isConnected(unit.team, source)) continue;

                for (Point2 dir : Geometry.d4) {
                    Tile candidate = Vars.world.tile(source.x + dir.x, source.y + dir.y);
                    if (candidate == null || candidate.build != null || candidate.floor() == null
                            || candidate.floor().isLiquid || candidate.solid() || candidate.block() != Blocks.air
                            || !RBlocks.cord.canPlaceOn(candidate, unit.team, 0)) continue;

                    float heat = deathMemory.dangerDistance(candidate);
                    float threat = NeoplasmStrategicPathfinder.getTileThreat(unit.team, candidate);
                    float score = unit.dst2(candidate.worldx(), candidate.worldy()) + heat * 36f + threat * 12f;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        if (best != null) best.setBlock(RBlocks.cord, unit.team);
    }

    @Override
    public Teamc findMainTarget(float x, float y, float range, boolean air, boolean ground) {
        Teamc wall = Units.findEnemyTile(unit.team, x, y, range * 1.15f, b -> b.block instanceof Wall);
        if (wall != null) return wall;
        Teamc turret = targetFlag(x, y, BlockFlag.turret, true);
        return turret != null ? turret : super.findMainTarget(x, y, range, air, ground);
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
            if (target != null && unit.type.faceTarget) unit.lookAt(target);
            return;
        }

        if (target == null && state.rules.waves && unit.team == state.rules.defaultTeam) {
            Tile spawner = getClosestSpawner();
            if (spawner != null) {
                if (!deathMemory.applyStrategicTarget(spawner, state.rules.dropZoneRadius + 130f, PathfinderCustom.costNone)) {
                    moveTo(spawner, state.rules.dropZoneRadius + 130f);
                }
            }
            return;
        }
        super.updateMovement();
    }
}
