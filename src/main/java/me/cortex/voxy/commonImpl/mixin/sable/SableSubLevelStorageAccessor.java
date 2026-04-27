package me.cortex.voxy.commonImpl.mixin.sable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage")
public interface SableSubLevelStorageAccessor {
    @Accessor(value = "folder", remap = false)
    Path voxy$getFolder();
}
