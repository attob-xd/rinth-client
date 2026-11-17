/**
 * Check, with Iris among the built-in Fabric mods: Iris finds its platform helpers with
 * {@code ServiceLoader.load(IrisPlatformHelpers.class).findFirst()} as it starts, through the
 * providers tools/fabricmods binds (ModServices, web-classlib's ServiceProviders). The browser
 * build's {@code ServiceLoader} found none, and the game crashed in pre-bootstrap. Compile it
 * against fabric/runtime/build/fabric-mods/classes. Prints "probe passed" when the helpers are
 * Iris's Fabric ones.
 */
public final class ServiceLoaderProbe {

    private ServiceLoaderProbe() {}

    public static void main(String[] args) {
        String found;
        try {
            found = net.irisshaders.iris.platform.IrisPlatformHelpers.getInstance().getClass().getName();
        } catch (Throwable e) {
            found = e.toString();
        }
        System.out.println(
                found.equals("net.irisshaders.iris.platform.IrisFabricHelpers")
                        ? "probe passed"
                        : "probe failed: " + found);
    }
}
