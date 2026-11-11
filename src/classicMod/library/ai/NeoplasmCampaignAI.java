package classicMod.library.ai;

import arc.Events;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import classicMod.content.RBlocks;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.world.Tile;

/**
 * Adds the real Neoplasm faction core to campaign sectors whose wave team is
 * explicitly set to Team.neoplastic by their SectorPreset rules.
 *
 * The campaign map supplies the spawn marker; the core is placed nearby on
 * open terrain. From there, NeoplasmFactionCore starts the Cardius colony and
 * provides baseline troops. This keeps the encounter separate from normal
 * Crux wave spawning.
 */
public final class NeoplasmCampaignAI{

    private static boolean initialized;

    private NeoplasmCampaignAI(){
    }

    public static void init(){
        if(initialized) return;
        initialized = true;

        Events.on(EventType.WorldLoadEvent.class, event -> {
            if(Vars.state == null || Vars.state.rules == null) return;
            if(!Vars.state.isCampaign() || Vars.state.rules.editor || Vars.state.rules.pvp) return;
            if(Vars.state.rules.waveTeam != Team.neoplastic) return;

            var rules = Vars.state.rules.teams.get(Team.neoplastic);
            rules.rtsAi = true;
            rules.aiCoreSpawn = true;
            rules.rtsMinSquad = 3;
            rules.rtsMaxSquad = 40;
            rules.rtsMinWeight = 0.9f;

            // Let map spawn markers and the campaign player core finish loading.
            Time.runTask(2f, NeoplasmCampaignAI::placeCampaignCore);
        });
    }

    private static void placeCampaignCore(){
        if(Vars.state == null || Vars.state.rules == null || !Vars.state.isCampaign()) return;
        if(Vars.state.rules.waveTeam != Team.neoplastic || Vars.net.client()) return;
        if(Team.neoplastic.cores().size > 0) return;

        Tile location = findCoreLocation();
        if(location == null){
            Log.err("[Project: Restoration] No suitable location for the Neoplasm campaign core.");
            return;
        }

        location.setBlock(RBlocks.neoplasmCore, Team.neoplastic, 0);
        if(location.build != null && location.build.team == Team.neoplastic){
            Log.info("[Project: Restoration] Neoplasm campaign core placed at @,@.", location.x, location.y);
        }else{
            Log.err("[Project: Restoration] Failed to place the Neoplasm campaign core.");
        }
    }

    private static Tile findCoreLocation(){
        Seq<Tile> spawns = Vars.spawner.getSpawns();
        Tile playerCore = Vars.state.rules.defaultTeam.core() == null
            ? null
            : Vars.state.rules.defaultTeam.core().tile;

        Tile best = null;
        float bestScore = -Float.MAX_VALUE;

        // Place the faction core close to a map enemy-spawn marker, but not
        // directly on top of it. Prefer a site farther from the player's core.
        for(Tile spawn : spawns){
            if(spawn == null) continue;

            for(int dy = -14; dy <= 14; dy++){
                for(int dx = -14; dx <= 14; dx++){
                    float spawnDistance = Mathf.dst2(dx, dy, 0f, 0f);
                    if(spawnDistance < 16f || spawnDistance > 196f) continue;

                    Tile candidate = Vars.world.tile(spawn.x + dx, spawn.y + dy);
                    if(!validCoreSite(candidate)) continue;

                    float playerDistance = playerCore == null
                        ? 0f
                        : Mathf.dst2(candidate.x, candidate.y, playerCore.x, playerCore.y);
                    float score = playerDistance - spawnDistance * 2f;
                    if(score > bestScore){
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }

        // Some custom campaign maps may not contain explicit spawn overlays.
        // Fall back to an open, placeable site away from the player core.
        if(best == null){
            for(int y = 4; y < Vars.world.height() - 4; y += 2){
                for(int x = 4; x < Vars.world.width() - 4; x += 2){
                    Tile candidate = Vars.world.tile(x, y);
                    if(!validCoreSite(candidate)) continue;

                    float score = playerCore == null
                        ? Mathf.dst2(x, y, Vars.world.width() / 2f, Vars.world.height() / 2f)
                        : Mathf.dst2(x, y, playerCore.x, playerCore.y);
                    if(score > bestScore){
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }

        return best;
    }

    private static boolean validCoreSite(Tile center){
        if(center == null || center.build != null) return false;
        if(!RBlocks.neoplasmCore.canPlaceOn(center, Team.neoplastic, 0)) return false;

        // CoreBlock is 3x3. Never overwrite an existing building or spawn site.
        for(int dy = -1; dy <= 1; dy++){
            for(int dx = -1; dx <= 1; dx++){
                Tile tile = Vars.world.tile(center.x + dx, center.y + dy);
                if(tile == null || tile.build != null || tile.overlay() == mindustry.content.Blocks.spawn) return false;
            }
        }
        return true;
    }
}
