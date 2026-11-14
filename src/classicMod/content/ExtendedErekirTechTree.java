package classicMod.content;

import arc.struct.Seq;
import mindustry.content.*;
import mindustry.ctype.UnlockableContent;
import mindustry.game.Objectives.*;
import mindustry.type.*;

import static classicMod.content.RBlocks.*;
import static mindustry.content.Blocks.*;
import static mindustry.content.Items.*;
import static mindustry.content.Liquids.*;
import static mindustry.content.SectorPresets.*;

public class ExtendedErekirTechTree {
    static TechTree.TechNode context = null;

    public static void load() {
        margeNode(radar, () -> {
            node(RBlocks.barrierProjector, Seq.with(
                    new OnSector(aegis)
            ), () -> {});

            node(RBlocks.shieldBreaker, Seq.with(
                    new SectorComplete(intersect)
            ), () -> {

            });

        });

        margeNode(diffuse, () -> {
            node(fracture, Seq.with(new OnSector(aegis)), () -> {
                node(horde,Seq.with(
                        new Research(RBlocks.slagCentrifuge),
                        new Produce(scrap)
                ), () -> {

                });
            });
        });

        margeNode(breach, () -> {
            node(tinyBreach);
        });

        margeNode(afflict, () -> {
            node(chrome, Seq.with(
                    new Research(RBlocks.heatReactor),
                    new Produce(fissileMatter)
            ), () -> {

            });
        });

        margeNode(duct, () -> {
            node(ductJunction);
        });

        margeNode(armoredDuct, () -> {
            node(surgeDuct);
        });

        margeNode(electricHeater, () -> {
            node(RBlocks.heatReactor, Seq.with(new OnSector(stronghold), new Produce(thorium) ,new Research(atmosphericConcentrator), new Produce(nitrogen)), () -> {

            });
        });

        margeNode(fluxReactor, () -> {
            node(cellSynthesisChamber, Seq.with(new Produce(carbide), new Produce(cyanogen), new Produce(phaseFabric)), () -> {});
        });

        margeNode(slagIncinerator, () -> {
            node(RBlocks.slagCentrifuge,Seq.with(new OnSector(crevice), new Produce(Items.sand), new Produce(Liquids.slag)), () -> {});
        });

        margeNode(basicAssemblerModule, () -> {
            node(droneCenter, Seq.with(new Produce(phaseFabric)), () -> {});
        });

        margeNodeSpecific(Liquids.slag, tungsten, () -> {
            nodeProduce(scrap, () -> nodeProduce(Liquids.gallium));
        });

        margeNodeSpecific(thorium, tungsten, () -> {
            nodeProduce(fissileMatter);
        });

        margeNode(reinforcedVault, () -> {
            node(reinforcedSafe);
        });

        margeNode(UnitTypes.vanquish, () -> {
            //node(ClassicUnitTypes.howit, () -> node(ClassicUnitTypes.mantel));
        });

        // Neoplasm campaign branch on Erekir.
        // These sector presets are introduced after Onset, then chained as a four-stage campaign.
        margeNode(onset, () -> {
            node(RSectorPresents.contagionOutpost, Seq.with(new SectorComplete(onset)), () -> {});
        });

        margeNode(RSectorPresents.contagionOutpost, () -> {
            node(RSectorPresents.viscousBasin, Seq.with(new SectorComplete(RSectorPresents.contagionOutpost)), () -> {});
        });

        margeNode(RSectorPresents.viscousBasin, () -> {
            node(RSectorPresents.arterialRift, Seq.with(new SectorComplete(RSectorPresents.viscousBasin)), () -> {});
        });

        margeNode(RSectorPresents.arterialRift, () -> {
            node(RSectorPresents.necroticCitadel, Seq.with(new SectorComplete(RSectorPresents.arterialRift)), () -> {});
        });

        // The Neoplasm construction line branches from Erekir's oxidation chamber.
        // Research costs are explicit instead of inheriting the placeholder 1-beryllium block costs.
        margeNode(oxidationChamber, () -> {
            node(heart,
                ItemStack.with(beryllium, 400, graphite, 250, silicon, 200),
                Seq.with(new SectorComplete(RSectorPresents.contagionOutpost)),
                () -> {
                    node(cord,
                        ItemStack.with(beryllium, 200, graphite, 100, silicon, 80),
                        () -> {
                            node(neoplasiaDrill,
                                ItemStack.with(beryllium, 300, graphite, 180, silicon, 120),
                                Seq.with(new SectorComplete(RSectorPresents.viscousBasin)),
                                () -> {}
                            );

                            node(oxideCrafter,
                                ItemStack.with(beryllium, 280, graphite, 160, silicon, 100),
                                Seq.with(new SectorComplete(RSectorPresents.viscousBasin)),
                                () -> {}
                            );

                            node(tole,
                                ItemStack.with(beryllium, 260, graphite, 180, silicon, 100),
                                Seq.with(new SectorComplete(RSectorPresents.viscousBasin)),
                                () -> {}
                            );

                            node(neoplasiaBomb,
                                ItemStack.with(beryllium, 220, graphite, 100, silicon, 100),
                                Seq.with(new SectorComplete(RSectorPresents.viscousBasin)),
                                () -> {}
                            );

                            node(bloom,
                                ItemStack.with(beryllium, 450, graphite, 250, silicon, 200, oxide, 100),
                                Seq.with(new SectorComplete(RSectorPresents.arterialRift)),
                                () -> {}
                            );

                            node(pore,
                                ItemStack.with(beryllium, 800, tungsten, 400, silicon, 350, oxide, 200),
                                Seq.with(new SectorComplete(RSectorPresents.necroticCitadel)),
                                () -> {}
                            );

                            // Early and mid-tier biological spawners.
                            node(renaleSpawner,
                                ItemStack.with(beryllium, 240, graphite, 120),
                                Seq.with(new SectorComplete(RSectorPresents.viscousBasin)),
                                () -> {}
                            );

                            node(muleSpawner,
                                ItemStack.with(beryllium, 300, graphite, 180),
                                Seq.with(new SectorComplete(RSectorPresents.viscousBasin)),
                                () -> {}
                            );

                            node(squidSpawner,
                                ItemStack.with(beryllium, 300, graphite, 180),
                                Seq.with(new SectorComplete(RSectorPresents.viscousBasin)),
                                () -> {}
                            );

                            node(walkySpawner,
                                ItemStack.with(beryllium, 350, graphite, 200, oxide, 120),
                                Seq.with(new SectorComplete(RSectorPresents.arterialRift)),
                                () -> {}
                            );

                            node(hydroBomberSpawner,
                                ItemStack.with(beryllium, 400, graphite, 250, oxide, 180),
                                Seq.with(new SectorComplete(RSectorPresents.arterialRift)),
                                () -> {}
                            );

                            node(siegeCrawlerSpawner,
                                ItemStack.with(beryllium, 650, tungsten, 350, graphite, 250),
                                Seq.with(new SectorComplete(RSectorPresents.necroticCitadel)),
                                () -> {}
                            );

                            node(sporeCarrierSpawner,
                                ItemStack.with(beryllium, 500, graphite, 300, oxide, 200),
                                Seq.with(new SectorComplete(RSectorPresents.necroticCitadel)),
                                () -> {}
                            );

                            // The three V9-inspired units are tied to campaign milestones.
                            node(polyctenidSpawner,
                                ItemStack.with(beryllium, 600, graphite, 300, oxide, 180, silicon, 150),
                                Seq.with(new SectorComplete(RSectorPresents.arterialRift)),
                                () -> {}
                            );

                            node(scolopendraSpawner,
                                ItemStack.with(beryllium, 700, tungsten, 400, graphite, 300, oxide, 200),
                                Seq.with(new SectorComplete(RSectorPresents.necroticCitadel)),
                                () -> {}
                            );

                            node(mycelidSpawner,
                                ItemStack.with(beryllium, 550, graphite, 350, oxide, 250, silicon, 150),
                                Seq.with(new SectorComplete(RSectorPresents.necroticCitadel)),
                                () -> {}
                            );
                        }
                    );
                }
            );
        });
    }


    private static void margeNode(UnlockableContent parent, Runnable children){ //from betamindy!
        context = TechTree.all.find(t -> t.content == parent);
        children.run();
    }

    static TechTree.TechNode findParentedContent(UnlockableContent parent){
        for (var t : TechTree.all){
            if (t.content != null) {
                if (t.content == parent) return t;
            }
        }
        return null;
    }

    private static void margeNodeSpecific(UnlockableContent parent, UnlockableContent previous, Runnable children){ //modification
        var parented = findParentedContent(previous);
        context = TechTree.all.find(t -> t.content == parent && t.parent == parented);
        children.run();
    }

    private static void node(UnlockableContent content, ItemStack[] requirements, Seq<Objective> objectives, Runnable children){
        TechTree.TechNode node = new TechTree.TechNode(context, content, requirements);
        if(objectives != null) node.objectives = objectives;

        TechTree.TechNode prev = context;
        context = node;
        children.run();
        context = prev;
    }

    private static void node(UnlockableContent content, ItemStack[] requirements, Runnable children){
        node(content, requirements, null, children);
    }

    private static void node(UnlockableContent content, Seq<Objective> objectives, Runnable children){
        node(content, content.researchRequirements(), objectives, children);
    }

    private static void node(UnlockableContent content, Runnable children){
        node(content, content.researchRequirements(), children);
    }

    private static void node(UnlockableContent block){
        node(block, () -> {});
    }

    private static void nodeProduce(UnlockableContent content, Seq<Objective> objectives, Runnable children){
        node(content, content.researchRequirements(), objectives.add(new Produce(content)), children);
    }

    private static void nodeProduce(UnlockableContent content, Runnable children){
        nodeProduce(content, Seq.with(), children);
    }

    private static void nodeProduce(UnlockableContent content){
        nodeProduce(content, Seq.with(), () -> {});
    }
}
