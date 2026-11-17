package rinth.render.webgl2;

import java.lang.reflect.Proxy;
import rinth.platform.api.Platform;
import rinth.platform.api.Platforms;
import rinth.platform.api.threads.PlatformThreads;

/**
 * Enough of a platform for game classes to load in tests: building a {@code VertexFormat} runs
 * {@code Mth}'s static initializer, which reaches {@code Util}'s, which asks the platform how many
 * background threads to make. Anything else the game asks for fails the test loudly.
 */
final class TestPlatform {

    private TestPlatform() {}

    static void install() {
        if (Platforms.isInstalled()) {
            return;
        }
        PlatformThreads inline = suggested -> 0;
        Platforms.install((Platform) Proxy.newProxyInstance(
                Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "threads" -> inline;
                    case "description" -> "render-webgl2 tests";
                    case "nanoTime" -> System.nanoTime();
                    case "toString" -> "TestPlatform";
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }
}
