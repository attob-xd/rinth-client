package rinth.classlib.substitutes.java.lang.ref;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.lang.ref.PhantomReference}, which TeaVM lacks: a weak reference that never gives its
 * referent back, enqueued once the referent is collected. Sodium's {@code NativeBuffer} frees leaked
 * native memory with these. Unlike the JDK's, it's a {@code WeakReference}: TeaVM's collector
 * tracks only those.
 */
public class TPhantomReference<T> extends WeakReference<T> {

    public TPhantomReference(T referent, @Nullable ReferenceQueue<? super T> queue) {
        super(referent, queue);
    }

    /** Always null, as the JDK's. */
    @Override
    public @Nullable T get() {
        return null;
    }
}
