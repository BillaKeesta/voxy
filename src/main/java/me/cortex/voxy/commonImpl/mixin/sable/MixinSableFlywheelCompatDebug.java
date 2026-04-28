package me.cortex.voxy.commonImpl.mixin.sable;

import dev.ryanhcode.sable.sublevel.SubLevel;
import me.cortex.voxy.commonImpl.compat.sable.SableLightingDebug;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge")
public class MixinSableFlywheelCompatDebug {
    @Inject(method = "createRenderInfo", at = @At("TAIL"), remap = false, require = 0)
    private static void voxy$debugCreateRenderInfo(Level level, SubLevel subLevel, CallbackInfo ci) {
        SableLightingDebug.flywheelRenderInfo(level, subLevel);
    }

    @Inject(method = "preVisualizationFrame", at = @At("TAIL"), remap = false, require = 0)
    private static void voxy$debugPreVisualizationFrame(Level level, float partialTick, CallbackInfo ci) {
        SableLightingDebug.flywheelFrame();
    }
}
