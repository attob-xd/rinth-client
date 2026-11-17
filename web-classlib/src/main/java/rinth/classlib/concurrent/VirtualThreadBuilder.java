package rinth.classlib.concurrent;

import rinth.classlib.substitutes.java.lang.TThread$Builder$OfVirtual;
import java.util.concurrent.ThreadFactory;
import org.jspecify.annotations.Nullable;

/**
 * What {@code Thread.ofVirtual()} returns. In a page every thread is already a coroutine, as cheap
 * as a virtual thread, so a virtual thread is an ordinary one: a daemon, unnamed unless named, as
 * the JDK makes them.
 */
public final class VirtualThreadBuilder implements TThread$Builder$OfVirtual {

    private String name = "";
    /** The next number for a counted name, or -1 for one name for every thread. */
    private long next = -1;

    private Thread.@Nullable UncaughtExceptionHandler handler;

    @Override
    public TThread$Builder$OfVirtual name(String name) {
        this.name = name;
        next = -1;
        return this;
    }

    @Override
    public TThread$Builder$OfVirtual name(String prefix, long start) {
        name = prefix;
        next = start;
        return this;
    }

    @Override
    public TThread$Builder$OfVirtual inheritInheritableThreadLocals(boolean inherit) {
        return this;
    }

    @Override
    public TThread$Builder$OfVirtual uncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler) {
        this.handler = handler;
        return this;
    }

    @Override
    public Thread unstarted(Runnable task) {
        String threadName = name;
        if (next >= 0) {
            threadName += next++;
        }
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        if (handler != null) {
            thread.setUncaughtExceptionHandler(handler);
        }
        return thread;
    }

    @Override
    public Thread start(Runnable task) {
        Thread thread = unstarted(task);
        thread.start();
        return thread;
    }

    @Override
    public ThreadFactory factory() {
        return this::unstarted;
    }
}
