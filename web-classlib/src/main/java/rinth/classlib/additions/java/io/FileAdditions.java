package rinth.classlib.additions.java.io;

import rinth.classlib.InstanceMethod;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/** {@code java.io.File} methods TeaVM lacks. Both views share TeaVM's one virtual file system. */
public final class FileAdditions {

    private FileAdditions() {}

    @InstanceMethod
    public static Path toPath(File self) {
        return Paths.get(self.getPath());
    }
}
