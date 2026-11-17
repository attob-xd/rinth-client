package rinth.testmod.mixin;

import rinth.testmod.TestMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An {@code @Inject}: a line on the title screen, from the language file, saying the mod is in,
 * whether its entrypoint ran, how many mods the loader has, and whether the accessor reads the splash.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void rinthTestmod$line(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        boolean splash = ((TitleScreenAccessor) (Object) this).rinthTestmod$getSplash() != null;
        Component line = Component.translatable(
                "rinth_testmod.loaded",
                FabricLoader.getInstance().getAllMods().size(),
                TestMod.started ? "yes" : "no",
                splash ? "yes" : "no");
        graphics.text(this.font, line, 2, this.height - 20, 0xFFFFFF55);
    }
}
