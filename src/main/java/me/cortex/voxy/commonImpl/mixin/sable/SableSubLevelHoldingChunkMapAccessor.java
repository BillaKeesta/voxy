package me.cortex.voxy.commonImpl.mixin.sable;

import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap")
public interface SableSubLevelHoldingChunkMapAccessor {
    @Accessor(value = "loadedHoldingChunks", remap = false)
    Long2ObjectMap<SubLevelHoldingChunk> voxy$getLoadedHoldingChunks();

    @Invoker(value = "getOrLoadHoldingChunk", remap = false)
    SubLevelHoldingChunk voxy$invokeGetOrLoadHoldingChunk(ChunkPos chunkPos, boolean generate);
}
