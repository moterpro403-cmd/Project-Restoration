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
import mindustry.world.meta.BlockFlag;

import static mindustry.Vars.state;

public class NeoplasmSymbiontAI extends NeoplasmFlyingAI {
    private static final float escortScanRadius = 150f;
    private static final float graftRadius = 96f;
    private static final float graftPulseTicks = 180f;
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
        final float[] bestScore = {Float.MAX_VALUE};
        Units.nearby(unit.team, unit.x, unit.y, escortScanRadius, other -> {
            if (other != unit && other.isValid() && other.type != unit.type
                    && other.hasWeapons() && NeoplasmAIController.isNeoplasmUnit(other)) {
                float distanceScore = unit.dst2(other.x, other.y) / Math.max(250f, other.maxHealth);
                if (distanceScore < bestScore[0]) {
                    bestScore[0] = distanceScore;
                    escortLeader = other;
                }
            }
        });
    }

    private void pulseTissueGraft() {
        // Repair the three most injured Neoplasm allies and reinforce them.
        Seq<Unit> allies = new Seq<>();
        Units.nearby(unit.team, unit.x, unit.y, graftRadius, ally -> {
            if (ally != unit && ally.isValid() && ally.healthf() < 0.999f
                    && NeoplasmAIController.isNeoplasmUnit(ally)) {
                allies.add(ally);
            }
        });
        allies.sort(ally -> ally.healthf());
        graftedCount = Math.min(3, allies.size);
        for (int i = 0; i < graftedCount; i++) {
            Unit ally = allies.get(i);
            float missingHealth = Math.max(0f, ally.maxHealth - ally.health);
            float graftAmount = Math.min(missingHealth, Math.max(40f, ally.maxHealth * 0.08f));
            if (graftAmount > 0f) ally.heal(graftAmount);
            ally.apply(StatusEffects.shielded, 240f);
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

                    float nearestDeath = deathMemory.dangerDistance(candidate);
                    float deathPenalty = nearestDeath < 80f ? (80f - nearestDeath) * 36f : 0f;
                    float score = unit.dst2(candidate.worldx(), candidate.worldy()) + deathPenalty;
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
        Teamc turret = targetFlag(x, y, BlockFlag.turret, true);
        return turret != null ? turret : super.findMainTarget(x, y, range, air, ground);
    }

    @Override
    public void updateMovement() {
        if (escortLeader != null && escortLeader.isValid()) {
            Tile leaderTile = escortLeader.tileOn();
            if (leaderTile != null && !unit.within(escortLeader, 52f)) {
                if (!deathMemory.avoidNearbyDanger(leaderTile)) {
                    moveTo(escortLeader, 44f);
                }
            } else if (target != null && unit.type.faceTarget) {
                // Hold formation near the escort while continuing to fire.
                unit.lookAt(target);
            }
            return;
        }

        if (target == null && state.rules.waves && unit.team == state.rules.defaultTeam) {
            Tile spawner = getClosestSpawner();
            if (spawner != null) {
                if (!deathMemory.avoidNearbyDanger(spawner)) {
                    moveTo(spawner, state.rules.dropZoneRadius + 130f);
                }
            }
            return;
        }
        super.updateMovement();
    }
}
