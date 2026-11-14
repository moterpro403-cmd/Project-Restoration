package classicMod.library.blocks.neoplasiaBlocks;

import classicMod.content.RUnitTypes;
import mindustry.game.Team;
import mindustry.type.UnitType;
import mindustry.content.UnitTypes;

/**
 * Shared soft population ceiling for the autonomous Neoplasm faction.
 * Counts only its own roster, leaving other teams' unit budgets untouched.
 */
public final class NeoplasmPopulation {
    public static final int maxTotal = 64;
    public static final int maxBlobs = 12;
    public static final int maxSiegeCrawlers = 4;
    public static final int maxSporeCarriers = 4;
    public static final int maxPolyctenids = 3;
    public static final int maxScolopendras = 8;
    public static final int maxMycelids = 5;

    private NeoplasmPopulation(){}

    public static int count(Team team){
        if(team == null) return 0;
        var data = team.data();
        return data.countType(RUnitTypes.blob)
            + data.countType(RUnitTypes.pallium)
            + data.countType(RUnitTypes.mule)
            + data.countType(RUnitTypes.worm)
            + data.countType(RUnitTypes.squid)
            + data.countType(RUnitTypes.miserable)
            + data.countType(RUnitTypes.hydroBomber)
            + data.countType(RUnitTypes.kalyx)
            + data.countType(RUnitTypes.matte)
            + data.countType(RUnitTypes.siegeCrawler)
            + data.countType(RUnitTypes.sporeCarrier)
            + data.countType(RUnitTypes.polyctenid)
            + data.countType(RUnitTypes.scolopendra)
            + data.countType(RUnitTypes.mycelid)
            + data.countType(UnitTypes.renale);
    }

    public static boolean canSpawn(Team team, UnitType type){
        if(team == null || type == null || type.isBanned() || count(team) >= maxTotal) return false;
        var data = team.data();
        if(type == RUnitTypes.blob && data.countType(type) >= maxBlobs) return false;
        if(type == RUnitTypes.siegeCrawler && data.countType(type) >= maxSiegeCrawlers) return false;
        if(type == RUnitTypes.sporeCarrier && data.countType(type) >= maxSporeCarriers) return false;
        if(type == RUnitTypes.polyctenid && data.countType(type) >= maxPolyctenids) return false;
        if(type == RUnitTypes.scolopendra && data.countType(type) >= maxScolopendras) return false;
        if(type == RUnitTypes.mycelid && data.countType(type) >= maxMycelids) return false;
        return true;
    }
}
