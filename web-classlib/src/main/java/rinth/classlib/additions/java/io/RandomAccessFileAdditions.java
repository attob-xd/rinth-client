package rinth.classlib.additions.java.io;

import rinth.classlib.InstanceMethod;
import rinth.classlib.nio.FileChannels;
import rinth.classlib.substitutes.java.nio.channels.TFileChannel;
import java.io.RandomAccessFile;

/** {@code java.io.RandomAccessFile} methods TeaVM lacks. */
public final class RandomAccessFileAdditions {

    private RandomAccessFileAdditions() {}

    /**
     * The file's channel, whose position is the file's pointer. Closing the channel closes the
     * file; closing the file is only noticed by the channel's next operation, which fails.
     */
    @InstanceMethod
    public static TFileChannel getChannel(RandomAccessFile self) {
        return FileChannels.of(self);
    }
}
