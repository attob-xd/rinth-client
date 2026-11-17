package rinth.testmod.mixin;

import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** An accessor: the title screen's private splash, read through an interface the screen gains. */
@Mixin(TitleScreen.class)
public interface TitleScreenAccessor {

    @Accessor("splash")
    SplashRenderer rinthTestmod$getSplash();
}
