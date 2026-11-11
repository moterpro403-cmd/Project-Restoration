package classicMod.library.ai;

import arc.Events;
import classicMod.content.RBlocks;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;

/**
 * Enables native RTS squad control for the dedicated Neoplasm team.
 *
 * Expansion, ore extraction, structure conversion and unit spawning stay in
 * the organic Cardius/Cord network. This hook only configures team-level AI.
 */
public final class NeoplasmFactionAI{

    private static boolean initialized;

    private NeoplasmFactionAI(){
    }

    public static void init(){
        if(initialized) return;
        initialized = true;

        Events.on(EventType.WorldLoadEvent.class, event -> {
            if(Vars.state == null || Vars.state.rules == null) return;
            if(Vars.state.rules.editor || Vars.state.rules.pvp) return;
            if(Vars.state.rules.defaultTeam == Team.neoplastic) return;

            var rules = Vars.state.rules.teams.get(Team.neoplastic);
            rules.rtsAi = true;
            rules.aiCoreSpawn = true;
            rules.rtsMinSquad = 3;
            rules.rtsMaxSquad = 40;
            rules.rtsMinWeight = 0.9f;
        });
    }
}
