package classicMod.library.blocks.neoplasiaBlocks;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.Angles;
import arc.struct.Seq;
import classicMod.content.RUnitTypes;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.graphics.Pal;
import mindustry.type.UnitType;
import mindustry.world.Tile;

public class CausticBirthingPod extends NeoplasmBlock {

    /** Time required to form a viable organism, in ticks. */
    public float incubationTime = 60f * 30f;
    /** Length of the visible opening/hatching animation, in ticks. */
    public float hatchDuration = 42f;

    /** Unit types that a pod may produce when it has no queued brood order. */
    public Seq<UnitType> broodTypes = new Seq<>();

    public TextureRegion[] stages = new TextureRegion[4];
    public Color hatchColor = Pal.neoplasm2;
    /** One-shot pods are used by the Heart's queued assault waves. */
    public boolean oneShot;

    public CausticBirthingPod(String name) {
        super(name);
        update = true;
    }

    @Override
    public void load() {
        super.load();
        for (int i = 0; i < stages.length; i++) {
            stages[i] = Core.atlas.find(name + "-stage" + (i + 1));
        }
    }

    @Override
    protected TextureRegion[] icons() {
        return new TextureRegion[]{Core.atlas.find(name)};
    }

    public class BirthingPodBuild extends NeoplasmBuilding {
        /** Incubation progress for the current organism. */
        public float progress;
        /** Remaining time in the visible hatching animation. */
        public float hatching;
        /** Short retry delay when the exit is blocked or the team has hit its unit cap. */
        private float retryTimer;
        /** Recently hatched flash timer. */
        private float hatchFlash;

        private UnitType currentBrood;
        private final Seq<UnitType> broodQueue = new Seq<>();

        public float fraction() {
            return incubationTime <= 0f ? 1f : Mathf.clamp(progress / incubationTime);
        }

        /** Adds an ordered organism to this pod's queue. */
        public void enqueueBrood(UnitType type) {
            if (type == null) return;
            if (currentBrood == null && progress <= 0f && hatching <= 0f) {
                currentBrood = type;
            } else {
                broodQueue.add(type);
            }
        }

        /** Number of queued or currently incubating organisms, excluding those already alive. */
        public int queuedCount() {
            return broodQueue.size + (currentBrood == null ? 0 : 1);
        }

        private UnitType selectBrood() {
            if (currentBrood != null) return currentBrood;

            if (!broodQueue.isEmpty()) {
                currentBrood = broodQueue.remove(0);
                return currentBrood;
            }

            if (broodTypes.isEmpty()) {
                currentBrood = RUnitTypes.miserable;
            } else {
                currentBrood = broodTypes.get(Mathf.random(0, broodTypes.size - 1));
            }
            return currentBrood;
        }

        private Tile findExit(UnitType type) {
            int firstRadius = Math.max(2, size / 2 + 1);
            for (int radius = firstRadius; radius <= firstRadius + 4; radius++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;

                        Tile candidate = Vars.world.tile(tile.x + dx, tile.y + dy);
                        if (candidate == null || candidate.block() != Blocks.air || candidate.build != null) continue;
                        if (candidate.floor() == null) continue;
                        if (!type.flying && candidate.floor().isLiquid) continue;
                        return candidate;
                    }
                }
            }
            return null;
        }

        private boolean hatch() {
            UnitType type = selectBrood();
            if (type == null || !Units.canCreate(team, type) || !NeoplasmBlock.trySpawn(team)) return false;

            Tile exit = findExit(type);
            if (exit == null) return false;

            Unit unit = type.create(team);
            float angle = Angles.angle(x, y, exit.worldx(), exit.worldy());
            unit.set(exit.worldx(), exit.worldy());
            unit.rotation(angle);
            unit.add();

            Fx.neoplasiaSmoke.at(x, y, hatchColor);
            Fx.neoplasiaSmoke.at(exit.worldx(), exit.worldy(), hatchColor);

            progress = 0f;
            hatching = 0f;
            retryTimer = 0f;
            hatchFlash = 24f;
            currentBrood = null;
            if (oneShot && tile != null) tile.setBlock(pipe, team);
            return true;
        }

        @Override
        public void update() {
            super.update();

            if (Vars.net.client() || !grown) return;

            if (hatchFlash > 0f) hatchFlash = Math.max(0f, hatchFlash - delta());
            if (retryTimer > 0f) retryTimer = Math.max(0f, retryTimer - delta());

            selectBrood();

            if (hatching > 0f) {
                hatching = Math.max(0f, hatching - delta());
                if (hatching <= 0f && !hatch()) {
                    // Keep the mature egg intact and retry later instead of repeatedly
                    // playing the opening animation while blocked or over unit cap.
                    retryTimer = 60f;
                }
                return;
            }

            if (progress < incubationTime) {
                progress = Math.min(incubationTime, progress + delta());
            }

            if (progress >= incubationTime && retryTimer <= 0f) {
                UnitType type = selectBrood();
                if (type != null && Units.canCreate(team, type) && findExit(type) != null) {
                    hatching = hatchDuration;
                }
            }
        }

        @Override
        public void updateBeat() {
            super.updateBeat();
            growCord(pipe);
        }

        @Override
        public void draw() {
            int frame = 0;
            if (hatching > 0f || hatchFlash > 0f) {
                frame = 3;
            } else {
                float f = fraction();
                if (f >= 0.70f) frame = 2;
                else if (f >= 0.42f) frame = 1;
                else if (f >= 0.18f) frame = 0;
            }

            drawBeat(1f, 1f, 0.2f);
            TextureRegion chosen = stages[frame] != null && stages[frame].found() ? stages[frame] : region;
            Draw.rect(chosen, x, y);
            Draw.color();
            Draw.reset();
        }
    }
}
