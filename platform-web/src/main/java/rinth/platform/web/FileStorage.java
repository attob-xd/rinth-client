package rinth.platform.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.jspecify.annotations.Nullable;
import rinth.platform.api.storage.PlatformStorage;
import rinth.platform.web.storage.WebFileSystem;

/**
 * The platform's storage in the browser: where the game's folders are (the entry point's
 * choice), and the page's file system for handing a world to a copy of the game in a worker and
 * back (ADR 0039).
 */
final class FileStorage implements PlatformStorage {

    private final PlatformStorage folders;
    private final WebFileSystem files;

    FileStorage(PlatformStorage folders, WebFileSystem files) {
        this.folders = folders;
        this.files = files;
    }

    @Override
    public String gameDirectory() {
        return folders.gameDirectory();
    }

    @Override
    public String assetsDirectory() {
        return folders.assetsDirectory();
    }

    @Override
    public @Nullable String assetIndex() {
        return folders.assetIndex();
    }

    @Override
    public byte[] handOver(String directory) {
        try {
            return files.handOver(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't hand over " + directory, e);
        }
    }

    @Override
    public void takeOver(byte[] bundle) {
        try {
            files.takeOver(bundle);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't take back the files a worker had", e);
        }
    }

    @Override
    public byte @Nullable [] takeChanges() {
        return files.takeChanges();
    }

    @Override
    public void storeChanges(byte[] changes) {
        files.storeChanges(changes);
    }
}
