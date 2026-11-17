package rinth.classlib.substitutes.java.util;

import static org.assertj.core.api.Assertions.assertThat;

import rinth.classlib.ServiceProviders;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServiceLoaderTest {

    interface Helpers {}

    record First() implements Helpers {}

    record Second() implements Helpers {}

    @AfterEach
    void unbind() {
        ServiceProviders.bind(service -> List.of());
    }

    @Test
    void findsNothingUnbound() {
        assertThat(TServiceLoader.load(Helpers.class).findFirst()).isEmpty();
        assertThat(TServiceLoader.load(Helpers.class).iterator().hasNext()).isFalse();
    }

    @Test
    void findsTheBoundProvidersInOrder() {
        ServiceProviders.bind(service -> service == Helpers.class ? List.of(new First(), new Second()) : List.of());

        assertThat(TServiceLoader.load(Helpers.class).findFirst()).contains(new First());
        assertThat(TServiceLoader.load(Helpers.class, null)).containsExactly(new First(), new Second());
        assertThat(TServiceLoader.load(Helpers.class).stream().map(TServiceLoader.Provider::type))
                .containsExactly(First.class, Second.class);
        assertThat(TServiceLoader.load(Runnable.class).findFirst()).isEmpty();
    }
}
