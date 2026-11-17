package rinth.classlib.substitutes.java.lang;

/** {@code java.lang.Thread.Builder.OfVirtual} (Java 21): see {@link TThread$Builder}. */
@SuppressWarnings("TypeName") // The name is the JDK's nested interface, as the class library maps it.
public interface TThread$Builder$OfVirtual extends TThread$Builder {

    @Override
    TThread$Builder$OfVirtual name(String name);

    @Override
    TThread$Builder$OfVirtual name(String prefix, long start);

    @Override
    TThread$Builder$OfVirtual inheritInheritableThreadLocals(boolean inherit);

    @Override
    TThread$Builder$OfVirtual uncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler);
}
