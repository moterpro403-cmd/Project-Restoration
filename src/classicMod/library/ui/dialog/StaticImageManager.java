package classicMod.library.ui.dialog;

import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import classicMod.ClassicMod;
import arc.graphics.g2d.TextureRegion;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

import java.util.concurrent.atomic.AtomicInteger;

import static mindustry.Vars.dataDirectory;

public class StaticImageManager extends BaseDialog {

    Dialog warningDelete = new Dialog();

    static Table ImageManger = new Table();

    public static void rebuildManager(){
        AtomicInteger i = new AtomicInteger();
        ImageManger.clearChildren();
        dataDirectory.child("prjRes-background").walk(fi -> {
            TextureRegion preview = ClassicMod.staticImageBackground.imageData.get(fi.nameWithoutExtension());
            if(preview == null) return;

            i.getAndIncrement();
            ImageManger.table(Styles.grayPanel, imData -> {
                imData.table(imTable -> {
                    imTable.image(preview).size(Vars.mobile ? 88f : 128f);
                }).pad(Vars.mobile ? 5f : 10f);
                imData.table(imButton -> {
                    imButton.add(fi.name()).pad(5f).row();
                    imButton.button("@delete", Icon.trash, () -> {
                        Vars.ui.showConfirm("@confirm", "@delete.file-disclaimer", () -> {
                            fi.delete();
                            rebuildManager();
                            ClassicMod.rebuildStaticImage(ClassicMod.staticImageBackground);
                        });
                    }).size(Vars.mobile ? 145f : 210f, Vars.mobile ? 54f : 64f);
                });
            });
            if (Vars.mobile || i.get() >= 2) {
                ImageManger.row();
                i.set(0);
            }
        });
    }

    public StaticImageManager() {
        super("@SIM.title");

        rebuildManager();
        addCloseButton();

        cont.add(new ScrollPane(ImageManger));
    }


}
