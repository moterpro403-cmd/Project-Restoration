package classicMod.library.blocks.neoplasiaBlocks;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.struct.Seq;
import classicMod.content.RBlocks;
import classicMod.library.ai.PathfinderExtended;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.game.Team;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;

/**
 * A true Mindustry core for the Neoplasm faction.
 *
 * The CoreBlock base class is important: it registers with TeamData.cores,
 * participates in enemy-core targeting and makes the faction defeatable.
 * The existing Cardius remains the organic colony seed and expansion brain.
 */
public class NeoplasmFactionCore extends CoreBlock{

    private static final String cardiusRegion = "neoplasm-cardius";

    public NeoplasmFactionCore(String name){
        super(name);
        size = 3;
        unitCapModifier = 30;
        itemCapacity = 4000;
        allowSpawn = true;
        commandable = true;
    }

    @Override
    public void load(){
        super.load();

        // Reuse the existing Cardius art so no placeholder core texture is needed.
        TextureRegion cardius = Core.atlas.find(cardiusRegion);
        if(cardius.found()){
            region = cardius;
            uiIcon = cardius;
            fullIcon = cardius;
        }
    }

    @Override
    public TextureRegion[] icons(){
        return new TextureRegion[]{region};
    }

    public class NeoplasmFactionCoreBuild extends CoreBuild{
        private float colonySeedTimer;

        @Override
        public void update(){
            super.update();

            if(team != Team.neoplastic || Vars.net.client() || Vars.state.isEditor()) return;

            // Give the colony time to load its world and vent index, then establish
            // one Cardius. After that, its original autonomous spread takes over.
            colonySeedTimer += delta();
            if(colonySeedTimer < 60f * 3f) return;
            colonySeedTimer = 0f;

            if(team.data().getCount(RBlocks.heart) > 0) return;

            Tile best = null;
            float bestDistance = Float.MAX_VALUE;

            for(Tile vent : PathfinderExtended.SteamVents){
                if(vent == null || vent.build != null || !heartFootprintClear(vent)) continue;
                if(!RBlocks.heart.canPlaceOn(vent, team, 0)) continue;

                float distance = Mathf.dst(x, y, vent.worldx(), vent.worldy());
                if(distance < bestDistance){
                    bestDistance = distance;
                    best = vent;
                }
            }

            if(best != null){
                best.setBlock(RBlocks.heart, team, 0);
                Fx.neoplasiaSmoke.at(best.worldx(), best.worldy());
            }
        }

        private boolean heartFootprintClear(Tile center){
            // Cardius is 3x3. Do not let the automatic seed overwrite unrelated
            // structures when the closest vent is in a crowded starting area.
            for(int dy = -1; dy <= 1; dy++){
                for(int dx = -1; dx <= 1; dx++){
                    Tile tile = Vars.world.tile(center.x + dx, center.y + dy);
                    if(tile == null) return false;
                    if(tile.build != null && !(tile.build instanceof CausticCord.CordBuild)) return false;
                }
            }
            return true;
        }
    }
}
