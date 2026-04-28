package me.cortex.voxy.commonImpl.mixin.sable;

import me.cortex.voxy.commonImpl.compat.sable.SableLightingDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.sodium.SodiumSubLevelRenderData")
public class MixinSableSodiumRenderDataDebug {
    @Inject(method = "updateChunks", at = @At("HEAD"), remap = false, require = 0)
    private void voxy$debugUpdateChunks(boolean important, CallbackInfo ci) {
        SableLightingDebug.sodiumUpdateChunks(important);
    }

    @Inject(method = "setDirty", at = @At("HEAD"), remap = false, require = 0)
    private void voxy$debugSetDirty(int x, int y, int z, boolean important, CallbackInfo ci) {
        SableLightingDebug.sodiumSetDirty(x, y, z, important);
    }
}
