package rinth.classlib.substitutes.java.lang;

import java.util.concurrent.ThreadFactory;

/**
 * {@code java.lang.Thread.Builder} (Java 21), which TeaVM's {@code Thread} lacks; Fabric mods make
 * their worker threads with it. A top-level interface named for the nested one, as
 * {@code TThread$State}; {@code Thread.ofVirtual} comes from {@code ThreadAdditions}.
 */
@SuppressWarnings("TypeName") // The name is the JDK's nested interface, as the class library maps it.
public interface TThread$Builder {

    TThread$Builder name(String name);

    TThread$Builder name(String prefix, long start);

    TThread$Builder inheritInheritableThreadLocals(boolean inherit);

    TThread$Builder uncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler);

    Thread unstarted(Runnable task);

    Thread start(Runnable task);

    ThreadFactory factory();
}
