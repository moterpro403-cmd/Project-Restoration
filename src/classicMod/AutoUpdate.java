package classicMod;

import arc.Events;
import arc.files.*;
import arc.util.*;
import arc.util.Http.HttpResponse;
import arc.util.io.Streams;
import arc.util.serialization.Jval;
import classicMod.library.EventTypeExtended;
import mindustry.mod.Mods.LoadedMod;

import java.net.URLClassLoader;

import static arc.Core.*;
import static mindustry.Vars.*;

public class AutoUpdate {

    public static final String repo = "VvSeanGTvV/Project-Restoration";

    public static LoadedMod mod;
    public static String url;

    public static float progress;
    public static String download;
    public static int latestBuild;
    private static String latest;
    private static String downloadExtension = ".jar";
    /** Indicates whether the installed build is newer than the latest release. */
    public static boolean overBuild;

    public static void load() {
        url = ghApi + "/repos/" + repo + "/releases/latest";
        mod = mods.getMod("restored-mind");
        if (mod == null || mod.file == null) {
            Log.err("Project: Restoration updater could not locate the installed mod file.");
            return;
        }

        try {
            Fi root = mod.file.isDirectory() ? mod.file : new ZipFi(mod.file);
            Fi metadata = root.child("mod.hjson");
            if (!metadata.exists()) throw new IllegalStateException("Installed mod has no mod.hjson.");
            Jval meta = Jval.read(metadata.readString());
            mod.meta.author = meta.getString("author");
            mod.meta.description = meta.getString("description");
        } catch (Throwable error) {
            Log.err("Could not read Project: Restoration metadata.");
            Log.err(error);
        }
    }

    private static int parseBuild(String tag) {
        if (tag == null || tag.isEmpty()) throw new IllegalArgumentException("Release tag is empty.");
        int start = tag.length();
        while (start > 0 && Character.isDigit(tag.charAt(start - 1))) start--;
        if (start == tag.length()) throw new IllegalArgumentException("Release tag has no numeric build suffix: " + tag);
        return Integer.parseInt(tag.substring(start));
    }

    public static void check(boolean checkOnly) {
        if (url == null || url.isEmpty()) {
            Log.err("Project: Restoration updater has no release API URL.");
            return;
        }

        Log.info("Checking for Project: Restoration updates.");
        Http.get(url, response -> {
            try {
                Jval json = Jval.read(response.getResultAsString());
                latest = json.getString("tag_name");
                latestBuild = parseBuild(latest);

                Jval assetValue = json.get("assets");
                if (assetValue == null || !assetValue.isArray()) {
                    throw new IllegalStateException("GitHub release did not contain an assets array.");
                }

                String jarUrl = null, zipUrl = null;
                for (int i = 0; i < assetValue.asArray().size; i++) {
                    Jval asset = assetValue.asArray().get(i);
                    String name = asset.getString("name");
                    String assetUrl = asset.getString("browser_download_url");
                    if (assetUrl == null || assetUrl.isEmpty() || name == null) continue;
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    if (lower.endsWith(".jar") && jarUrl == null) jarUrl = assetUrl;
                    else if (lower.endsWith(".zip") && zipUrl == null) zipUrl = assetUrl;
                }

                if (jarUrl != null) {
                    download = jarUrl;
                    downloadExtension = ".jar";
                } else if (zipUrl != null) {
                    download = zipUrl;
                    downloadExtension = ".zip";
                } else {
                    throw new IllegalStateException("Latest GitHub release has no JAR or ZIP asset.");
                }

                int modBuild = Integer.parseInt(ClassicMod.BuildVer);
                overBuild = modBuild > latestBuild;
                Log.info("GitHub build " + latestBuild + " : installed build " + modBuild);

                if (modBuild < latestBuild && !checkOnly && !headless && mod != null && mod.meta != null) {
                    ui.showCustomConfirm(
                        "@updater.restored-mind.name",
                        bundle.format("updater.restored-mind.info", mod.meta.version, latest),
                        "@updater.restored-mind.load", "@ok",
                        AutoUpdate::update, () -> {}
                    );
                }
            } catch (Throwable error) {
                Log.err("Could not process Project: Restoration release information.");
                Log.err(error);
            }
        }, error -> {
            Log.err("Could not check Project: Restoration updates.");
            Log.err(error);
        });
    }

    public static void update() {
        if (download == null || download.isEmpty()) {
            Log.err("Project: Restoration update was not started because no release asset was selected.");
            return;
        }

        progress = 0f;
        if (!headless) {
            ui.loadfrag.show("@updater.restored-mind.updating");
            ui.loadfrag.setProgress(() -> progress);
        }

        Http.get(download, AutoUpdate::handle, error -> {
            Log.err("Project: Restoration update download failed.");
            Log.err(error);
            if (!headless) app.post(ui.loadfrag::hide);
        });
    }

    public static void handle(HttpResponse response) {
        Fi file = tmpDirectory.child(repo.replace("/", "") + downloadExtension);
        try {
            file.delete();
            Streams.copyProgress(response.getResultAsStream(), file.write(false), response.getContentLength(), 4096, p -> progress = p);

            if (!file.exists() || file.length() <= 0L) {
                throw new IllegalStateException("The downloaded mod archive is empty.");
            }
            Fi metadata = new ZipFi(file).child("mod.hjson");
            if (!metadata.exists()) {
                throw new IllegalStateException("The downloaded release does not contain mod.hjson at its root.");
            }
            Jval.read(metadata.readString());

            // Close the loaded classloader only after the replacement archive is downloaded and validated.
            try {
                if (mod != null && mod.loader instanceof URLClassLoader loader) loader.close();
                if (mod != null) mod.loader = null;
            } catch (Throwable error) {
                Log.err(error);
            }

            mods.importMod(file).setRepo(repo);
            file.delete();

            if (!headless) {
                app.post(ui.loadfrag::hide);
                ui.showInfoOnHidden("@mods.reloadexit", app::exit);
            }
        } catch (Throwable error) {
            Log.err("Project: Restoration update could not be installed; discarding the downloaded file.");
            Log.err(error);
            file.delete();
            if (!headless) app.post(ui.loadfrag::hide);
        }
    }
}
