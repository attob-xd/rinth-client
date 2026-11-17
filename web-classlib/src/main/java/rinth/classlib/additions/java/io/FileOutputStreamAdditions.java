package rinth.classlib.additions.java.io;

import rinth.classlib.InstanceMethod;
import rinth.classlib.nio.FileChannels;
import rinth.classlib.substitutes.java.nio.channels.TFileChannel;
import java.io.FileOutputStream;

/** {@code java.io.FileOutputStream} methods TeaVM lacks. */
public final class FileOutputStreamAdditions {

    private FileOutputStreamAdditions() {}

    /**
     * The stream's channel, which writes through the stream and so shares its position. TeaVM's
     * stream hides its file handle, so the channel can't seek or size the file (see
     * {@code rinth.classlib.nio.StreamFileChannel}); locking works.
     */
    @InstanceMethod
    public static TFileChannel getChannel(FileOutputStream self) {
        return FileChannels.of(self);
    }
}
