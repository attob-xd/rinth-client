package rinth.classlib.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VirtualThreadBuilderTest {

    @Test
    void makesDaemonThreadsNamedAsAsked() {
        ThreadFactory factory = new VirtualThreadBuilder().name("worker-", 3).factory();
        Thread first = factory.newThread(() -> {});
        Thread second = factory.newThread(() -> {});

        assertThat(first.getName()).isEqualTo("worker-3");
        assertThat(second.getName()).isEqualTo("worker-4");
        assertThat(first.isDaemon()).isTrue();
        assertThat(new VirtualThreadBuilder().unstarted(() -> {}).getName()).isEmpty();
    }

    @Test
    void startsTheTaskWithItsHandler() throws InterruptedException {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread thread = new VirtualThreadBuilder()
                .uncaughtExceptionHandler((t, e) -> caught.set(e))
                .start(() -> {
                    throw new IllegalStateException("from the task");
                });
        thread.join();

        assertThat(caught.get()).hasMessage("from the task");
    }
}
