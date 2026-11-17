package rinth.app.web;

import org.jspecify.annotations.Nullable;
import rinth.platform.api.storage.PlatformStorage;

/** Where the browser build keeps its files: under {@code WebFileSystem.ROOT}, which survives a reload. */
record WebStorage(
        String gameDirectory,
        String assetsDirectory,
        @Nullable String assetIndex) implements PlatformStorage {

    static final WebStorage DEFAULT = new WebStorage("/incari/game", "/incari/assets", null);

    /**
     * The page's {@code ?instance=}: an instance's own game directory (worlds, options, profiles, mod
     * switches), as a launcher keeps its instances apart. The assets are shared. An empty or unusable id
     * is the default directory.
     */
    static WebStorage forInstance(String instance) {
        if (instance == null || !instance.matches("[a-z0-9][a-z0-9-]{0,39}")) {
            return DEFAULT;
        }
        return new WebStorage("/incari/instances/" + instance + "/game", DEFAULT.assetsDirectory(), null);
    }
}
