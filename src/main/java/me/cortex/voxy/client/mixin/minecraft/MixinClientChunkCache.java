package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache implements ICheekyClientChunkCache {
    @Unique
    private static final boolean BOBBY_INSTALLED = FabricLoader.getInstance().isModLoaded("bobby");
    @Unique
    private static final boolean VOXY_DIRECT_CLIENT_CHUNK_INGEST = Boolean.getBoolean("voxy.debugDirectClientChunkIngest");
    @Unique
    private static final boolean VOXY_INGEST_TRACE = VOXY_DIRECT_CLIENT_CHUNK_INGEST || Boolean.getBoolean("voxy.debugIngestTrace");
    @Unique
    private static final AtomicInteger VOXY_INGEST_TRACE_COUNT = new AtomicInteger();

    @Shadow
    private volatile ClientChunkCache.Storage storage;
    @Shadow @Final
    private ClientLevel level;

    @Unique
    private static void voxy$traceIngest(String source, int x, int y, int z, boolean queued) {
        if (!VOXY_INGEST_TRACE || VOXY_INGEST_TRACE_COUNT.getAndIncrement() >= 256) {
            return;
        }
        Logger.warn("Voxy direct client ingest " + source + " section=[" + x + ", " + y + ", " + z + "] queued=" + queued);
    }

    @Unique
    private static void voxy$traceChunkIngest(String source, int x, int z, int queued, int total) {
        if (!VOXY_INGEST_TRACE || VOXY_INGEST_TRACE_COUNT.getAndIncrement() >= 256) {
            return;
        }
        Logger.warn("Voxy direct client ingest " + source + " chunk=[" + x + ", " + z + "] queuedSections=" + queued + "/" + total);
    }

    @Override
    public @Nullable LevelChunk voxy$cheekyGetChunk(int x, int z) {
        //This doesnt do the in range check stuff, it just gets the chunk at all costs
        var chunk = this.storage.getChunk(this.storage.getIndex(x, z));
        if (chunk == null) {
            return null;
        }
        //Verify that the position of the chunk is the same as the requested position
        if (chunk.getPos().x == x && chunk.getPos().z == z) {
            return chunk;//The chunk is at the requested position
        }
        //Otherwise return null
        return null;
    }

    @Inject(method = "drop", at = @At("HEAD"))
    public void voxy$captureChunkBeforeUnload(ChunkPos pos, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && BOBBY_INSTALLED) {
            var chunk = this.voxy$cheekyGetChunk(pos.x, pos.z);
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At("TAIL"))
    public void voxy$ingestOnChunkPacket(
            int x,
            int z,
            FriendlyByteBuf buffer,
            CompoundTag tag,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
            CallbackInfoReturnable<LevelChunk> cir) {
        if (!VOXY_DIRECT_CLIENT_CHUNK_INGEST || !VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        LevelChunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }
        WorldIdentifier worldId = WorldIdentifier.of(chunk.getLevel());
        int queued = 0;
        int total = 0;
        int sectionY = chunk.getMinSection() - 1;
        for (var section : chunk.getSections()) {
            sectionY++;
            if (section == null) {
                continue;
            }
            total++;
            if (VoxelIngestService.rawIngest(worldId, section, x, sectionY, z, null, null)) {
                queued++;
            }
        }
        voxy$traceChunkIngest("chunkPacketRaw", x, z, queued, total);
    }

    @Inject(method = "onLightUpdate", at = @At("TAIL"))
    public void voxy$ingestOnLightUpdate(LightLayer lightType, SectionPos pos, CallbackInfo ci) {
        if (!VOXY_DIRECT_CLIENT_CHUNK_INGEST || !VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        LevelChunk chunk = this.voxy$cheekyGetChunk(pos.x(), pos.z());
        if (chunk == null) {
            voxy$traceIngest("lightUpdateMissingChunk", pos.x(), pos.y(), pos.z(), false);
            return;
        }
        int sectionIndex = pos.y() - (this.level.getMinBuildHeight() >> 4);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            voxy$traceIngest("lightUpdateBadSection", pos.x(), pos.y(), pos.z(), false);
            return;
        }

        var section = chunk.getSections()[sectionIndex];
        var lightingProvider = this.level.getLightEngine();
        var blockLight = lightingProvider.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
        var skyLight = lightingProvider.getLayerListener(LightLayer.SKY).getDataLayerData(pos);

        boolean queued = VoxelIngestService.rawIngest(
                WorldIdentifier.of(this.level),
                section,
                pos.x(),
                pos.y(),
                pos.z(),
                blockLight == null ? null : blockLight.copy(),
                skyLight == null ? null : skyLight.copy());
        voxy$traceIngest("lightUpdate:" + lightType, pos.x(), pos.y(), pos.z(), queued);
    }
}
