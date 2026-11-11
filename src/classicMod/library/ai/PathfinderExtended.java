package classicMod.library.ai;

import arc.Events;
import arc.math.geom.QuadTree;
import arc.struct.*;
import arc.util.Log;
import classicMod.library.blocks.legacyBlocks.LegacyCommandCenter;
import classicMod.library.blocks.neoplasiaBlocks.CausticHeart;
import mindustry.Vars;
import mindustry.ai.Pathfinder;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.world.*;
import mindustry.world.meta.Attribute;

import java.util.*;

public class PathfinderExtended extends Pathfinder {
    public static final int fieldVent = 1, fieldCommandCenter = 2, fieldOres = 3;

    public static Seq<Tile> SteamVents = new Seq<>();
    public static Seq<Tile> Ores = new Seq<>();

    public PathfinderExtended(){}

    public static class SteamVentField extends Flowfield {
        public SteamVentField() {
            refreshRate = 900;
        }

        protected void getPositions(IntSeq out) {
            for (Tile tile : SteamVents) {
                if (tile.floor().attributes.get(Attribute.steam) <= 0f) continue;
                float steam = 0f;
                for (int dy = -1; dy < 2; dy++) {
                    for (int dx = -1; dx < 2; dx++) {
                        Tile vents = Vars.world.tile(tile.x + dx, tile.y + dy);
                        if (vents == null || vents.build instanceof CausticHeart.HeartBuilding || vents.floor().attributes.get(Attribute.steam) <= 0f) continue;
                        steam += vents.floor().attributes.get(Attribute.steam);
                    }
                }
                if (steam >= 9f) out.add(tile.array());
            }
        }
    }

    public static class CommandCenterField extends Flowfield {
        protected void getPositions(IntSeq out) {
            Seq<Building> builds = new Seq<>();
            Seq<Building> valid = new Seq<>();
            QuadTree<Building> buildings = team.data().buildingTree;
            buildings.getObjects(builds);
            for (var build : builds) {
                if (build.team == team && build instanceof LegacyCommandCenter.LegacyCommandCenterBuild) valid.add(build);
            }
            for (var center : valid) out.add(center.tile.array());
        }
    }

    public static class OresField extends Flowfield {
        public OresField() {
            refreshRate = 900;
        }

        protected void getPositions(IntSeq out) {
            for (Tile tile : Ores) {
                if (tile == null) continue;
                var floorDrop = tile.drop();
                var wallDrop = tile.wallDrop();
                if (classicMod.content.RVars.CordCanDrill.contains(floorDrop) ||
                    classicMod.content.RVars.CordCanDrill.contains(wallDrop)) {
                    out.add(tile.array());
                }
            }
        }
    }

    public static void preloadAddons(){
        Events.on(EventType.WorldLoadEvent.class, event -> {
            Ores.clear();
            SteamVents.clear();

            for (Tile tile : Vars.world.tiles) {
                if (tile.drop() != null || tile.wallDrop() != null) Ores.add(tile);
            }

            for (Tile tile : Vars.world.tiles) {
                if (tile.floor() == null || tile.block() != Blocks.air) continue;
                if (tile.floor().attributes.get(Attribute.steam) >= 1f) {
                    float steam = 0f;
                    for (int dy = -1; dy < 2; dy++) {
                        for (int dx = -1; dx < 2; dx++) {
                            Tile vents = Vars.world.tile(tile.x + dx, tile.y + dy);
                            if (vents == null || vents.floor().attributes.get(Attribute.steam) <= 0f) continue;
                            steam += vents.floor().attributes.get(Attribute.steam);
                        }
                    }
                    if (steam >= 9f) SteamVents.add(tile);
                }
            }
        });
    }

    public static void addonFieldTypes(){
        fieldTypes.add(SteamVentField::new);
        fieldTypes.add(CommandCenterField::new);
        fieldTypes.add(OresField::new);
    }
}
