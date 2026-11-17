package rinth.modtoggles.mixin;

import com.terraformersmc.modmenu.gui.widget.entries.ModListEntry;
import com.terraformersmc.modmenu.util.mod.Mod;
import rinth.modtoggles.ModSwitches;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A mod's row in Mod Menu's list: its ON/OFF switch drawn at the row's right, and clicks on it. */
@Mixin(ModListEntry.class)
public abstract class ModListEntryMixin extends ObjectSelectionList.Entry<ModListEntry> {

    @Shadow
    @Final
    public Mod mod;

    @Inject(method = "extractContent", at = @At("TAIL"))
    private void rinthModtoggles$switch(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a, CallbackInfo ci) {
        ModSwitches.draw(graphics, mod.getId(), getContentRight(), getContentY(), getContentHeight(), mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void rinthModtoggles$click(
            MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (ModSwitches.click(
                mod.getId(), getContentRight(), getContentY(), getContentHeight(), event.x(), event.y())) {
            cir.setReturnValue(true);
        }
    }
}
