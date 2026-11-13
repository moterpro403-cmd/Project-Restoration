package classicMod.library.blocks.neoplasiaBlocks;

import mindustry.game.Team;
import arc.util.Time;

/**
 * Team-wide expansion cooldown. Every Heart, cord and spawner shares one
 * budget, preventing each connected segment from expanding independently.
 */
public final class NeoplasmGrowthLimiter {
    // General expansion remains deliberately slow to control heart/spawner growth.
    private static final float expansionInterval = 45f;
    // Cords are the faction's front line; let the network extend about 1.9x faster.
    private static final float cordExpansionInterval = 24f;
    private static final float[] nextExpansion = new float[256];
    private static final float[] nextCordExpansion = new float[256];

    static{
        java.util.Arrays.fill(nextExpansion, -1f);
        java.util.Arrays.fill(nextCordExpansion, -1f);
    }

    private NeoplasmGrowthLimiter(){}

    public static boolean allowExpansion(Team team){
        if(team == null || team.id < 0 || team.id >= nextExpansion.length) return false;
        if(Time.time < nextExpansion[team.id]) return false;
        nextExpansion[team.id] = Time.time + expansionInterval;
        return true;
    }

    /** Faster, separate budget for cord placement; does not accelerate spawners. */
    public static boolean allowCordExpansion(Team team){
        if(team == null || team.id < 0 || team.id >= nextCordExpansion.length) return false;
        if(Time.time < nextCordExpansion[team.id]) return false;
        nextCordExpansion[team.id] = Time.time + cordExpansionInterval;
        return true;
    }
}
