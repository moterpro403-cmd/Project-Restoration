
package classicMod;

import arc.*;
import arc.files.*;
import arc.func.Func;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import classicMod.content.*;
import classicMod.library.EventTypeExtended;
import classicMod.library.ai.*;
import classicMod.library.ui.UIExtended;
import classicMod.library.ui.dialog.*;
import classicMod.library.ui.menu.*;
import classicMod.library.unitType.unit.JumpingUnit;
import mindustry.Vars;
import mindustry.ai.types.*;
import mindustry.entities.units.AIController;
import mindustry.game.EventType;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;
import mindustry.type.*;
import mindustry.type.unit.NeoplasmUnitType;
import mindustry.ui.FileChooser;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.*;
import mindustry.ui.fragments.MenuFragment;

import java.io.IOException;
import java.util.Objects;

import static arc.Core.*;
import static classicMod.AutoUpdate.overBuild;
import static classicMod.content.RVars.idcMap;
import static classicMod.content.RVars.useNewSound;
import static classicMod.library.ui.dialog.StaticImageManager.rebuildManager;
import static classicMod.library.ui.menu.MenuUI.*;
import static mindustry.Vars.*;

//project-restoration is the current use

public class ClassicMod extends Mod {
    /** Mod's current Version **/
    public static String ModVersion = "6.0 Beta";
    /** Mod's current Build **/
    public static final String BuildVer = "16";
    /** Mod's internal name **/
    public static String internalMod = "restored-mind";
    public static LoadedMod resMod = mods.locateMod(internalMod);
    /** Mindustry's Contributors taken from internal **/
    public static Seq<String> contributors = new Seq<>();
    SettingsMenuDialog.SettingsTable restorationSettings;
    public static StaticImageBackground staticImageBackground;

    static void defaultBackground(StaticImageBackground staticImageBackground) {
        staticImageBackground.image.add("ohno");
        staticImageBackground.imageData.put("ohno", atlas.find("ohno"));
    }

    public ClassicMod() {
        RVars.init_RVars();
        Events.on(ClientLoadEvent.class, e -> {
            try {
                staticImageBackground = new StaticImageBackground();
                loadStaticImage();
                rebuildStaticImage(staticImageBackground);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }

            boolean flareUseWraith = settings.getBool("flare-use-v5");
            boolean april2022 = settings.getBool("aprils-2022");

            boolean usePlanetBG = settings.getBool("use-planetmenu");
            boolean uselastPlanet = settings.getBool("use-lastplanet-bg");

            loadSettings();
            Core.app.post(UIExtended::init);
            boolean ignoreWarning = settings.getBool("ignore-warning");
            Log.info(pathFile());

            getContributors();
            graphics.setTitle(settings.getAppName() + " | " + resMod.meta.displayName + " " + BuildVer);

            ui.menufrag.addButton(Core.bundle.get("credits"), Icon.info, () -> {
                UIExtended.creditsCutsceneDialog.show();
            });

            Time.runTask(10f, () -> {
                UIExtended.postInit();

                if (usePlanetBG) {
                    if (uselastPlanet) {
                        Reflect.set(MenuFragment.class, ui.menufrag, "renderer", new MainMenuRenderer(SortedPlanet));
                    } else {
                        Reflect.set(MenuFragment.class, ui.menufrag, "renderer", new MainMenuRenderer(random));
                    }
                } else {
                    loadStaticBackground();
                }

                if (flareUseWraith) {
                    UnitType flare = content.unit("flare");
                    flare.controller = u -> new ReplacementFlyingAI();
                }

                if (april2022) {
                    UnitType flare = content.unit("flare");
                    Item blast = content.item("blast-compound");
                    flare.fullIcon = flare.uiIcon = flare.region = Core.atlas.find(internalMod + "-flarogus-full");
                    blast.fullIcon = blast.uiIcon = Core.atlas.find(internalMod + "-blast");
                }

                if (!ignoreWarning) {
                    Dialog dialog = new Dialog();
                    dialog.setFillParent(true);
                    Table warningDialog = new Table(Styles.grayPanel) {
                    }.center();

                    warningDialog.table(information -> {
                        information.table(character -> {
                            character.add("@mod.restored-mind.lucine.name").row();
                            character.image(Core.atlas.find(internalMod + "-lucine-smug")).pad(10f).size(140f).scaling(Scaling.stretch);
                        }).right();
                        information.table(text -> {
                            text.add("@mod.restored-mind.earlyaccess.text").pad(20f).row();
                            CheckBox box = new CheckBox("@mod.restored-mind.popup-warning");
                            box.update(() -> {
                                box.setChecked(Core.settings.getBool("ignore-warning"));
                            });
                            box.changed(() -> {
                                settings.put("ignore-warning", box.isChecked());
                            });
                            text.add(box).left().row();
                        });

                    }).expand().row();
                    warningDialog.table(buttons -> {
                        buttons.button("@ok", Icon.ok, dialog::hide).padBottom(10f).size(210.0F, 54.0F).center();
                    }).growX().center();

                    dialog.cont.bottom();
                    dialog.cont.add(warningDialog);

                    dialog.show();
                }
            });

            LoadedMod lastModVer = mods.locateMod("classicv5");
            if (lastModVer != null) {
                ui.showCustomConfirm("@mod.restored-mind.conflictwarning.title", "@mod.restored-mind.conflictwarning.text", "Yes", "No", () -> {
                    lastModVer.file.delete();
                    ui.showInfoOnHidden("@mods.reloadexit", () -> {
                        Log.info("Exiting to reload mods.");
                        Core.app.exit();
                    });
                }, () -> {
                    Log.err("Disabled, not to have conflicts here!");
                });
            }
        });
    }

    public String pathFile() {
        return resMod.file.path();
    }

    public void getContributors() {
        contributors = Seq.with(Core.files.internal("contributors").readString("UTF-8").split("\n"));
    }

    public static Func<String, String> getModBundle = value -> bundle.get("mod." + value);

    public static Func<String, String> getStatBundle = value -> bundle.get("stat." + value);

    public static void rebuildStaticImage(StaticImageBackground staticImageBackground) {
        staticImageBackground.image.clear();
        staticImageBackground.imageData.clear();

        defaultBackground(staticImageBackground);
        dataDirectory.child("prjRes-background").walk(fi -> {
            staticImageBackground.image.add(fi.nameWithoutExtension());
            Texture image = new Texture(fi);
            staticImageBackground.imageData.put(fi.nameWithoutExtension(), new TextureRegion(image));
        });
        rebuildManager();
        Events.fire(new EventTypeExtended.UpdateSlide(staticImageBackground.image.size - 1));
    }

    public void loadStaticImage() throws IOException {
        var dest = dataDirectory + "/";
        ZipFi zip = new ZipFi(Fi.get(resMod.file.absolutePath()));
        zip.child("prjRes-background").walk(fi -> {
            Fi newDir = new Fi(dest + "/" + fi.path());
            if (fi.isDirectory()) {
                newDir.mkdirs();
            } else {
                fi.copyTo(newDir);
            }
        });
    }

    public void loadStaticBackground() {
        if (settings.getBool("use-staticmenu")) {
            if (!headless) {
                Reflect.set(ui.menufrag, "renderer", new MainMenuRenderer(staticImageBackground));
            } else {
                Log.warn("Headless detected! Background loading skipped.");
                Log.infoTag("Project: Restoration", "Headless detected! Background loading skipped.");
            }
        }
    }

    @Override
    public void init() {
        if (settings.getBool("use-planetmenu")) MenuUI.load();
        AutoUpdate.load();
        AutoUpdate.check(settings.getBool("ignore-update"));

        if (!headless) {
            resMod = mods.locateMod("restored-mind");
            resMod.meta.version = BuildVer;

            StringBuilder contributors = new StringBuilder(getModBundle.get(resMod.meta.name + ".author"));
            contributors.append("\n\n").append("[#FCC21B]Credits[]");
            int i = 0;
            while (bundle.has("mod." + resMod.meta.name + "-credits." + i)) {
                contributors.append("\n        ").append(getModBundle.get(resMod.meta.name + "-credits." + i));
                i++;
            }
            resMod.meta.author = contributors.toString();
        }
    }

    boolean isChangedDirectory = false;

    boolean isValidExtension(Seq<String> acceptedExtension, Fi file) {
        boolean hasExtension = false;
        for (String extension : acceptedExtension) {
            hasExtension = file.extension().equalsIgnoreCase(extension);
            if (hasExtension) break;
        }
        return hasExtension;
    }

    private void loadSettings() {
        ObjectMap<String, Boolean> defaultsRestorationBoolean = new ObjectMap<>();
        ui.settings.shown(() -> {
            AutoUpdate.check(true);
            for (var tabSet : restorationSettings.getSettings()) {
                if (tabSet instanceof SettingsMenuDialog.SettingsTable.CheckSetting) {
                    defaultsRestorationBoolean.put(tabSet.name, settings.getBool(tabSet.name));
                }
            }
        });
        ui.settings.hidden(() -> {
            int difference = 0;
            for (var tabSet : restorationSettings.getSettings()) {
                if (tabSet instanceof SettingsMenuDialog.SettingsTable.CheckSetting) {
                    if (Objects.equals(tabSet.name, "use-planetmenu")) {
                        if (settings.getBool(tabSet.name) != defaultsRestorationBoolean.get(tabSet.name)) {
                            difference++;
                        }
                    }

                    if (Objects.equals(tabSet.name, "use-staticmenu")) {
                        if (settings.getBool(tabSet.name) != defaultsRestorationBoolean.get(tabSet.name)) {
                            difference++;
                        }
                    }
                }
            }
            if (difference >= 1 || isChangedDirectory) {
                Vars.ui.showInfoOnHidden("@mod.restored-mind.restart", () -> {
                    Log.info("Project: Restoration restarting");
                    Core.app.exit();
                });
            }
        });
        ui.settings.addCategory("@setting.restored-mind", Icon.bookOpen, t -> {
            t.pref(new UIExtended.Banner("restored-mind-cat", -1));
            t.pref(new UIExtended.Separator("restored-graphic"));
            if (!settings.getBool("use-planetmenu")) t.checkPref("use-staticmenu", false);
            else settings.put("use-staticmenu", false);

            if (settings.getBool("use-staticmenu")) {
                t.pref(new UIExtended.SliderEventSetting("staticimage", 0, 0, staticImageBackground.image.size - 1, 1, stringProc -> {
                    return (stringProc >= staticImageBackground.image.size) ? staticImageBackground.image.get(stringProc - 1) : staticImageBackground.image.get(stringProc);
                }));
                t.pref(new UIExtended.ButtonSetting("staticimage-manager", Icon.book, () -> UIExtended.staticImageManager.show()));

                // Updated Mindustry v8 multi-file chooser API
                t.pref(new UIExtended.ButtonSetting("staticimage-upload", Icon.upload, () -> {
                    FileChooser.open("jpg", "png", "jpeg").submitMulti(files -> {
                        for (Fi file : files) {
                            try {
                                var dest = dataDirectory + "/prjRes-background";
                                if (isValidExtension(new Seq<>(new String[]{
                                    "jpg",
                                    "png",
                                    "jpeg"
                                }), file)) {
                                    Fi newDir = new Fi(dest + "/" + file.name());
                                    if (file.isDirectory()) {
                                        newDir.mkdirs();
                                    } else {
                                        file.copyTo(newDir);
                                    }
                                    rebuildStaticImage(staticImageBackground);
                                } else {
                                    Vars.ui.showErrorMessage("@data.invalid-image");
                                }
                            } catch (IllegalArgumentException var3) {
                                Vars.ui.showErrorMessage("@data.invalid-image");
                            } catch (Exception var4) {
                                var4.printStackTrace();
                                if (var4.getMessage() != null && var4.getMessage().contains("too short")) {
                                    Vars.ui.showErrorMessage("@data.invalid-image");
                                } else {
                                    Vars.ui.showException(var4);
                                }
                            }
                        }
                    });
                }));
            } else {
                settings.put("staticimage", 0);
            }

            if (!settings.getBool("use-staticmenu")) t.checkPref("use-planetmenu", false);
            else settings.put("use-planetmenu", false);
            if (settings.getBool("use-planetmenu")) t.checkPref("use-lastplanet-bg", false);
            else settings.put("use-lastplanet-bg", false);

            t.pref(new UIExtended.Separator("restored-annoying-window"));
            t.pref(new UIExtended.ButtonSetting("check-update", Icon.up, () -> AutoUpdate.check(settings.getBool("ignore-update")), 32, true));
            t.checkPref("ignore-warning", false);
            t.checkPref("ignore-update", false);

            t.pref(new UIExtended.Separator("restored-april"));
            t.checkPref("flare-use-v5", false);
            t.checkPref("aprils-2022", false);

            if (false) {
                t.pref(new UIExtended.Separator("restored-updates"));
                t.checkPref("beta-update", false);
            }

            t.pref(new UIExtended.Separator("restored-content-menu"));
            t.checkPref("content-use-new-sound", false);

            t.row();
            if (false) {
                t.pref(new UIExtended.Separator("restored-content-addon"));
                t.checkPref("content-classic", false);
                t.checkPref("content-v4", false);
            }

            t.pref(new UIExtended.Separator("restored-link"));
            t.pref(new UIExtended.ButtonSetting("restored-youtube", Icon.play, () -> Core.app.openURI("https://www.youtube.com/@vvseangtvv"), 32, true));
            t.pref(new UIExtended.ButtonSetting("restored-github", Icon.github, () -> Core.app.openURI("https://github.com/VvSeanGTvV/Project-Restoration"), 32, true));
            t.pref(new UIExtended.Separator("restored-information"));
            t.row();
            t.pref(new UIExtended.ButtonSetting(getModBundle.get(resMod.meta.name + "-debug.unlock"), Icon.lockOpen, () -> UIExtended.contentUnlockDebugDialog.show(), 32, true));
            t.row();
            t.add(new Table(info -> {
                info.add("Mod Version: " + ModVersion).color(Pal.lightishGray).padTop(4f).row();
                info.add("Build Version: " + BuildVer).color(Pal.lightishGray).padTop(4f).row();
                info.add(new Table() {{
                    add("Latest Release: ").color((!overBuild) ? Pal.lightishGray : Pal.redLight);
                    if (!overBuild) add(new Image(Icon.ok)).color(Pal.heal).size(16f).center();
                    else add(new Image(Icon.cancel)).color(Pal.remove).size(16f).center();
                }}).padTop(4f).row();
                info.add(new Table() {{
                    add("Development Release: ").color((overBuild) ? Pal.lightishGray : Pal.redLight);
                    if (overBuild) add(new Image(Icon.ok)).color(Pal.heal).size(16f).center();
                    else add(new Image(Icon.cancel)).color(Pal.remove).size(16f).center();
                }}).padTop(4f).row();
            }));
            if (!settings.has("unlocks" + "-launched-planetary")) settings.put("unlocks" + "-launched-planetary", false);
            restorationSettings = t;
        });
    }

    public static void unlockAll() {
        for (var blocks : content.blocks()) {
            blocks.unlock();
        }
        for (var sectors : content.sectors()) {
            sectors.unlock();
        }
        for (var units : content.units()) {
            units.unlock();
        }
        for (var liquids : content.liquids()) {
            liquids.unlock();
        }
        for (var items : content.items()) {
            items.unlock();
        }
        for (var effect : content.statusEffects()) {
            effect.unlock();
        }
    }

    public static void lockAll() {
        for (var blocks : content.blocks()) {
            blocks.clearUnlock();
        }
        for (var sectors : content.sectors()) {
            sectors.clearUnlock();
        }
        for (var units : content.units()) {
            units.clearUnlock();
        }
        for (var liquids : content.liquids()) {
            liquids.clearUnlock();
        }
        for (var items : content.items()) {
            items.clearUnlock();
        }
        for (var effect : content.statusEffects()) {
            effect.clearUnlock();
        }
    }

    public void registerConstructors() {
        int jumperID = EntityMapping.register("jumper", JumpingUnit::new);
        Log.info("registered jumper: " + jumperID);
    }

    @Override
    public void loadContent() {
        registerConstructors();

        useNewSound = settings.getBool("content-use-new-sound");
        new RItems().load();
        new OverridableContent().loadOverride();
        new RBullets().load();
        new RUnitTypes().load();
        new RBlocks().load();
        new RSectorPresents().load();
        new RPlanets().load();

        new RSerpuloTechTree().load();
        new ExtendedErekirTechTree().load();

        NeoplasmDeathMemory.load();

        PathfinderExtended.preloadAddons();
        PathfinderExtended.addonFieldTypes();

        RMusic.load();

        // Wrap the factory instead of replacing every unit's controller.
        // Player-controlled CommandAI and mod-specific AI implementations are preserved.
        Events.on(EventType.ContentInitEvent.class, event -> {
            for (UnitType type : content.units()) {
                if (type.controller == null) continue;

                var originalController = type.controller;

                // Decorate Neoplasm AI at runtime. The assigned FlyingAI, GroundAI,
                // HugAI, SuicideBomberAI, SteamHugAI, etc. remain the actual AI logic.
                if (type instanceof NeoplasmUnitType) {
                    type.controller = unit -> {
                        var controller = originalController.get(unit);
                        if (controller instanceof AIController ai) {
                            return new NeoplasmDeathAvoidanceAI(ai);
                        }
                        return controller;
                    };
                    continue;
                }

                type.controller = unit -> {
                    var controller = originalController.get(unit);
                    if (!unit.team.isAI()) return controller;

                    if (controller instanceof FlyingAI) return new ReplacementFlyingAI();
                    if (controller instanceof GroundAI) return new ReplacementGroundAI();
                    return controller;
                };
            }
        });
    }
}
