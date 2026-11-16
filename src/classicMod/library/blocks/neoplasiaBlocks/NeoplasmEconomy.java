package classicMod.library.blocks.neoplasiaBlocks;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Runtime resource requirements for selected advanced Neoplasm production. */
public final class NeoplasmEconomy {
    private static boolean applied;
    private NeoplasmEconomy(){}

    public static synchronized void apply(){
        if(applied) return;
        try{
            addSpawnFeed("scolopendraSpawner", "tungsten");
            addSpawnFeed("siegeCrawlerSpawner", "tungsten");
            addSpawnFeed("polyctenidSpawner", "beryllium");
            applied = true;
        }catch(Throwable error){
            throw new IllegalStateException("Could not apply Neoplasm resource recipes", error);
        }
    }

    private static void addSpawnFeed(String blockField, String itemField) throws Exception{
        Class<?> blocks = Class.forName("classicMod.content.RBlocks");
        Class<?> items = Class.forName("mindustry.content.Items");
        Class<?> stack = Class.forName("mindustry.type.ItemStack");
        Object block = blocks.getField(blockField).get(null);
        Object item = items.getField(itemField).get(null);
        Object pair = stack.getMethod("with", Object[].class)
            .invoke(null, (Object)new Object[]{item, Integer.valueOf(1)});
        Object array = Array.newInstance(stack, 0);
        block.getClass().getMethod("consumeItems", array.getClass()).invoke(block, pair);
    }

    /** Preserve population checks and charge one Graphite only for Blob creation. */
    public static boolean canCreate(Object heart, Object team, Object type){
        try{
            Class<?> teamClass = Class.forName("mindustry.game.Team");
            Class<?> unitClass = Class.forName("mindustry.type.UnitType");
            Method original = heart.getClass().getMethod("canCreateOriginal", teamClass, unitClass);
            if(!Boolean.TRUE.equals(original.invoke(heart, team, type))) return false;
            Object blob = Class.forName("classicMod.content.RUnitTypes").getField("blob").get(null);
            if(type != blob) return true;
            Object module = heart.getClass().getField("items").get(heart);
            Class<?> itemClass = Class.forName("mindustry.type.Item");
            Object graphite = Class.forName("mindustry.content.Items").getField("graphite").get(null);
            Method get = module.getClass().getMethod("get", itemClass);
            if(((Integer)get.invoke(module, graphite)).intValue() < 1) return false;
            module.getClass().getMethod("remove", itemClass, Integer.TYPE)
                .invoke(module, graphite, Integer.valueOf(1));
            return true;
        }catch(InvocationTargetException error){
            return false;
        }catch(Throwable error){
            return false;
        }
    }
}
