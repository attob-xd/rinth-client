package rinth.testmod;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/** The test mod's client entrypoint: it notes that it ran, for the title screen's line (TitleScreenMixin). */
public class TestMod implements ClientModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static boolean started;

    @Override
    public void onInitializeClient() {
        started = true;
        LOGGER.info(
                "Rinth test mod started: {} mods, game directory {}",
                FabricLoader.getInstance().getAllMods().size(),
                FabricLoader.getInstance().getGameDir());
    }
}
