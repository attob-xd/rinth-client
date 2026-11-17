package rinth.testmod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.resources.SplashManager;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A MixinExtras injector: every splash is the mod's own, from its language file. */
@Mixin(SplashManager.class)
public class SplashManagerMixin {

    @ModifyReturnValue(method = "getSplash", at = @At("RETURN"))
    private SplashRenderer rinthTestmod$splash(SplashRenderer original) {
        return new SplashRenderer(Component.translatable("rinth_testmod.splash"));
    }
}
