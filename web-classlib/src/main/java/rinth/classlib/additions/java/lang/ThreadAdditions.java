package rinth.classlib.additions.java.lang;

import rinth.classlib.InstanceMethod;
import rinth.classlib.concurrent.VirtualThreadBuilder;
import rinth.classlib.substitutes.java.lang.TThread$Builder$OfVirtual;
import java.time.Duration;

/** {@code java.lang.Thread} methods TeaVM lacks. */
public final class ThreadAdditions {

    private ThreadAdditions() {}

    @InstanceMethod
    @SuppressWarnings("deprecation") // getId is the same number; threadId is what callers use now.
    public static long threadId(Thread self) {
        return self.getId();
    }

    /**
     * A spin-wait hint. In a page, threads are coroutines that only switch when one yields, so a
     * spinning thread must yield or the thread it waits for never runs.
     */
    @SuppressWarnings("ThreadPriorityCheck") // Yielding is the only way another coroutine runs.
    public static void onSpinWait() {
        Thread.yield();
    }

    public static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(Math.max(0L, duration.toMillis()));
    }

    /** Virtual threads (Java 21): ordinary ones here, see {@link VirtualThreadBuilder}. */
    public static TThread$Builder$OfVirtual ofVirtual() {
        return new VirtualThreadBuilder();
    }

    public static Thread startVirtualThread(Runnable task) {
        return ofVirtual().start(task);
    }

    /**
     * Nothing to set: a page has one class loader, which every thread already uses. Netty sets it
     * on its helper threads.
     */
    @InstanceMethod
    public static void setContextClassLoader(Thread self, ClassLoader loader) {}

    /**
     * Whether the thread is alive is all TeaVM's {@code Thread} tracks: a live one is runnable (a
     * coroutine is either running or ready to), any other terminated, including one never started.
     */
    @InstanceMethod
    public static Thread.State getState(Thread self) {
        return self.isAlive() ? Thread.State.RUNNABLE : Thread.State.TERMINATED;
    }
}
