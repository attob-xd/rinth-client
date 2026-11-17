package rinth.fabric;

import com.mojang.logging.LogUtils;
import rinth.fabric.generated.FabricModTable;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Starts the Fabric mods built into the client (ADR 0065). tools/fabricmods puts a call to
 * {@link #start} in Minecraft's constructor, once it has set the game directory, where Fabric Loader
 * starts mods: before the options, key mappings and resources are made. The mods' {@code main}
 * initializers run, then their {@code client} ones. One that fails is logged and the rest still
 * run, where Fabric would stop the game: a browser tab can't be restarted with the mod removed.
 *
 * <p>Render thread, during the game's construction.
 */
public final class FabricBoot {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FabricBoot() {}

    public static void start(Minecraft minecraft) {
        FabricLoaderImpl.INSTANCE.setUp(
                EnvType.CLIENT,
                minecraft.gameDirectory.toPath(),
                FabricModTable.MINECRAFT,
                minecraft,
                FabricModTable.mods(),
                FabricModTable.entrypoints());
        LOGGER.info("Fabric mods built in: {}", FabricLoaderImpl.INSTANCE.getAllMods());
        // Mod Menu's switches: the game's code already runs this session's combination.
        FabricLoaderImpl.INSTANCE.setSwitches(ModSwitchState.CONTROL);
        if (!ModSwitchState.off().isEmpty()) {
            FabricLoaderImpl.INSTANCE.switchOff(ModSwitchState.off());
            LOGGER.info("Fabric mods switched off: {}", ModSwitchState.off());
        }
        initialize("main", ModInitializer.class, ModInitializer::onInitialize);
        initialize("client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
    }

    private static <T> void initialize(String key, Class<T> type, Consumer<T> initializer) {
        List<EntrypointContainer<T>> containers;
        try {
            containers = FabricLoaderImpl.INSTANCE.getEntrypointContainers(key, type);
        } catch (RuntimeException e) {
            LOGGER.error("Couldn't make the mods' {} entrypoints", key, e);
            return;
        }
        for (EntrypointContainer<T> container : containers) {
            String mod = container.getProvider().getMetadata().getId();
            try {
                initializer.accept(container.getEntrypoint());
            } catch (RuntimeException | LinkageError e) {
                LOGGER.error("Fabric mod {} failed in its {} initializer; the game goes on without it", mod, key, e);
            }
        }
    }
}
