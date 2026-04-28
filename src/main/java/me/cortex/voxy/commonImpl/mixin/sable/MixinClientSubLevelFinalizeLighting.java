package me.cortex.voxy.commonImpl.mixin.sable;

import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import me.cortex.voxy.commonImpl.compat.sable.SableClientChunkRetention;
import me.cortex.voxy.commonImpl.compat.sable.SableLightingDebug;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.ClientSubLevel")
public class MixinClientSubLevelFinalizeLighting {
    @Shadow(remap = false)
    private int latestSkyLightScale;

    @Shadow(remap = false)
    public native ClientLevel getLevel();

    @Shadow(remap = false)
    public native BoundingBox3dc boundingBox();

    @Inject(method = "setFinalized", at = @At("TAIL"), remap = false)
    private void voxy$invalidateInitialSkyLightScale(CallbackInfo ci) {
        this.latestSkyLightScale = -1;
        SableLightingDebug.subLevelFinalized(this.getLevel());
        SableClientChunkRetention.flushPendingChunkPackets(this.getLevel());
    }

    @Inject(method = "computeSubLevelSkyLight", at = @At("RETURN"), cancellable = true, remap = false)
    private void voxy$useCachedShadowChunkSkyLight(Pose3dc pose, CallbackInfoReturnable<Integer> cir) {
        int vanillaSkyLight = cir.getReturnValue();
        BoundingBox3dc bounds = this.boundingBox();
        if (vanillaSkyLight > 0) {
            SableLightingDebug.skyLightDecision(vanillaSkyLight, 0, false, pose, bounds);
            return;
        }

        int fallbackSkyLight = SableClientChunkRetention.getChunkBackedSubLevelSkyLight(this.getLevel(), pose, bounds);
        int debugFallbackSkyLight = Math.max(0, fallbackSkyLight);
        SableLightingDebug.skyLightDecision(vanillaSkyLight, debugFallbackSkyLight, true, pose, bounds);
        if (fallbackSkyLight <= 0) {
            SableLightingDebug.skyLightZeroContext(
                    SableClientChunkRetention.describeSubLevelSkyLightSamples(this.getLevel(), pose, bounds)
            );
        }
        if (fallbackSkyLight > 0) {
            cir.setReturnValue(fallbackSkyLight);
        }
    }

    @Inject(method = "getLatestSkyLightScale", at = @At("RETURN"), remap = false)
    private void voxy$debugLatestSkyLightScale(CallbackInfoReturnable<Integer> cir) {
        SableLightingDebug.latestSkyLightScaleRead((dev.ryanhcode.sable.sublevel.ClientSubLevel) (Object) this, cir.getReturnValue());
    }
}
