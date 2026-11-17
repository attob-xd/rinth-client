package rinth.modtoggles.mixin;

import com.terraformersmc.modmenu.util.mod.fabric.FabricMod;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mod Menu's list leaves out the mods the build hid (libraries inside other mods' jars, and
 * mods/hidden.txt's), as it does the ones its own settings hide. They still run.
 */
@Mixin(value = FabricMod.class, remap = false)
public abstract class FabricModMixin {

    @Inject(method = "isHidden", at = @At("HEAD"), cancellable = true)
    private void rinthModtoggles$hiddenByBuild(CallbackInfoReturnable<Boolean> cir) {
        String id = ((FabricMod) (Object) this).getId();
        if (FabricLoaderImpl.INSTANCE
                .switches()
                .map(control -> control.hidden(id))
                .orElse(false)) {
            cir.setReturnValue(true);
        }
    }
}
