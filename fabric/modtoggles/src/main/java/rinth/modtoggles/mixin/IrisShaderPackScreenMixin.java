package rinth.modtoggles.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris's screen for shader packs where it can't run them. The browser build runs Iris as on Vulkan
 * (fabric/README.md: its mixin plugin sees no desktop OpenGL), so Iris offers to switch the game
 * to OpenGL and close it, which can't work in a browser. Here it says why there are no shader packs,
 * and its button goes back instead.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.sodium.config.ShaderPackScreenPlaceholder")
public abstract class IrisShaderPackScreenMixin extends Screen {

    private static final String MESSAGE = "Shader packs need desktop OpenGL, which a browser doesn't have: Rinth"
            + " draws with WebGL 2.\nIris runs here without them.";

    @Shadow
    private Screen parent;

    private IrisShaderPackScreenMixin(Component title) {
        super(title);
    }

    @ModifyConstant(
            method = "init",
            constant =
                    @Constant(
                            stringValue =
                                    "Iris cannot run when using Vulkan. Would you like to switch to OpenGL?\nThis will close your game."))
    private String rinthModtoggles$browserMessage(String message) {
        return MESSAGE;
    }

    @ModifyConstant(method = "<init>", constant = @Constant(stringValue = "Switch"))
    private String rinthModtoggles$okLabel(String label) {
        return "OK";
    }

    @Inject(method = "switchToVk", at = @At("HEAD"), cancellable = true)
    private void rinthModtoggles$goBack(Button button, CallbackInfo ci) {
        ci.cancel();
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
