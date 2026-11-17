package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.ModMetadataImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** fabric/api's built-in mods find their assets, as the build keeps them, through paths. */
class ModContainerPathsTest {

    @TempDir
    Path game;

    private ModContainerImpl mod() {
        ModContainerImpl mod = new ModContainerImpl(
                new ModMetadataImpl("testmod", "1.0", "Test", "", "*", List.of(), List.of(), List.of(), ""));
        FabricLoaderImpl.INSTANCE.setUp(EnvType.CLIENT, game, "26.3", null, List.of(mod), List.of());
        return mod;
    }

    @Test
    void copiesAnAssetOutOnFirstUse() throws Exception {
        Optional<Path> icon = mod().findPath("assets/testmod/icon.png");

        assertThat(icon).contains(game.resolve(".fabric/mods/testmod/assets/testmod/icon.png"));
        assertThat(Files.readString(icon.orElseThrow())).isEqualTo("an icon");
    }

    @Test
    void makesTheConfigDirectoryWhenAsked() {
        mod();

        assertThat(FabricLoaderImpl.INSTANCE.getConfigDir())
                .isEqualTo(game.resolve("config"))
                .isDirectory();
    }

    @Test
    @SuppressWarnings("deprecation") // getPath is what mods built against older loaders call.
    void findsNothingTheBuildDidntKeep() {
        ModContainerImpl mod = mod();

        assertThat(mod.findPath("fabric.mod.json")).isEmpty();
        assertThat(mod.findPath("../../secret")).isEmpty();
        assertThat(mod.getPath("/assets/testmod/missing.png"))
                .isEqualTo(game.resolve(".fabric/mods/testmod/assets/testmod/missing.png"))
                .doesNotExist();
    }
}
