package rinth.tools.fabricmods;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

/** Names {@link OfflineMixinService} to Mixin ({@code mixin.bootstrapService}). */
public final class OfflineMixinServiceBootstrap implements IMixinServiceBootstrap {

    public OfflineMixinServiceBootstrap() {}

    @Override
    public String getName() {
        return "Rinth/offline";
    }

    @Override
    public String getServiceClassName() {
        return OfflineMixinService.class.getName();
    }

    @Override
    public void bootstrap() {}
}
