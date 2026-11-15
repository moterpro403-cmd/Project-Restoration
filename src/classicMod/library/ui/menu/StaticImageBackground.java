package classicMod.library.ui.menu;

import arc.graphics.Texture;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.struct.*;
import static arc.Core.*;
import static mindustry.Vars.*;

/**
 * Static menu image background.
 *
 * Android note: draw the source texture directly. The previous implementation
 * copied the full screen through a square FrameBuffer every frame, which
 * allocated a large extra GPU texture and added an unnecessary render pass.
 */
public class StaticImageBackground extends MenuBackground {
    public ObjectMap<String, TextureRegion> imageData = new ObjectMap<>();
    public Seq<String> image = new Seq<>();

    // Textures loaded from user-provided image files. Atlas regions are not owned here.
    public Seq<Texture> ownedTextures = new Seq<>();

    public void disposeImages(){
        for(Texture texture : ownedTextures){
            if(texture != null){
                texture.dispose();
            }
        }
        ownedTextures.clear();
    }

    @Override
    public void render(){
        if(image.isEmpty()) return;

        int selected = Math.max(0, Math.min(settings.getInt("staticimage"), image.size - 1));
        TextureRegion region = imageData.get(image.get(selected));
        if(region == null) return;

        Draw.color();
        Draw.rect(region, graphics.getWidth() / 2f, graphics.getHeight() / 2f,
            graphics.getWidth(), graphics.getHeight());
        Draw.color();
    }
}
