package classicMod.library.blocks.neoplasiaBlocks;

import classicMod.content.RBlocks;
import mindustry.content.Items;
import mindustry.type.ItemStack;

/** Runtime resource requirements for selected advanced Neoplasm production. */
public final class NeoplasmEconomy{
    private static boolean applied;

    private NeoplasmEconomy(){}

    public static synchronized void apply(){
        if(applied) return;

        // Apply recipes only after RBlocks.load() has created all spawner blocks.
        RBlocks.scolopendraSpawner.consumeItems(ItemStack.with(Items.tungsten, 1));
        RBlocks.siegeCrawlerSpawner.consumeItems(ItemStack.with(Items.tungsten, 1));
        RBlocks.polyctenidSpawner.consumeItems(ItemStack.with(Items.beryllium, 1));

        applied = true;
    }
}
