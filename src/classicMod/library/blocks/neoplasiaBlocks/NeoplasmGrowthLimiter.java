package classicMod.library.blocks.neoplasiaBlocks;

import mindustry.game.Team;
import arc.util.Time;

/**
 * Team-wide expansion cooldown. Every Heart, cord and spawner shares one
 * budget, preventing each connected segment from expanding independently.
 */
public final class NeoplasmGrowthLimiter {
    private static final float expansionInterval = 45f;
    private static final float[] nextExpansion = new float[256];

    static{
        java.util.Arrays.fill(nextExpansion, -1f);
    }

    private NeoplasmGrowthLimiter(){}

    public static boolean allowExpansion(Team team){
        if(team == null || team.id < 0 || team.id >= nextExpansion.length) return false;
        if(Time.time < nextExpansion[team.id]) return false;
        nextExpansion[team.id] = Time.time + expansionInterval;
        return true;
    }
}
