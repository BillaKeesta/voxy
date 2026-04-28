package me.cortex.voxy.commonImpl.compat.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class SableClientChunkRetention {
    private static final int RETAINED_CHUNK_PADDING = 1;
    private static final long RETAINED_CHUNK_SWEEP_INTERVAL_TICKS = 1L;
    private static final long PENDING_CHUNK_PACKET_TTL_TICKS = 400L;
    private static final int MAX_PENDING_CHUNK_PACKETS = 8192;

    private static final Map<ClientLevel, RetentionState> RETAINED_CHUNKS = new WeakHashMap<>();

    private static boolean sableUnavailable;
    private static boolean replayingPendingChunkPacket;
    private static boolean skyLightFallbackUnavailable;

    private SableClientChunkRetention() {
    }

    public static boolean retainChunkIfNeeded(ClientLevel level, ChunkPos chunkPos) {
        if (sableUnavailable) {
            return false;
        }

        try {
            if (!isChunkProtected(level, chunkPos, false)) {
                return false;
            }

            RetentionState state = RETAINED_CHUNKS.computeIfAbsent(level, ignored -> new RetentionState());
            long chunkKey = chunkPos.toLong();
            state.retainedChunks.add(chunkKey);
            bootstrapChunkIfLoaded(level, state, chunkKey);
            return true;
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
            return false;
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Sable client chunk retention after direct access failed", e);
            sableUnavailable = true;
            return false;
        }
    }

    public static boolean shouldStoreShadowChunk(ClientLevel level, ChunkPos chunkPos) {
        if (sableUnavailable) {
            SableLightingDebug.shadowStoreDecision(chunkPos.x, chunkPos.z, "sable-unavailable");
            return false;
        }

        try {
            if (isSablePlotChunk(level, chunkPos.x, chunkPos.z)) {
                SableLightingDebug.shadowStoreDecision(chunkPos.x, chunkPos.z, "sable-plot");
                return false;
            }

            boolean protectedChunk = isChunkProtected(level, chunkPos, true);
            SableLightingDebug.shadowStoreDecision(
                    chunkPos.x,
                    chunkPos.z,
                    protectedChunk ? "allowed" : "not-protected-finalized"
            );
            return protectedChunk;
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
            return false;
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Sable client chunk retention after shadow chunk check failed", e);
            sableUnavailable = true;
            return false;
        }
    }

    public static boolean deferChunkPacketIfNeeded(
            ClientPacketListener listener,
            ClientLevel level,
            ClientboundLevelChunkWithLightPacket packet
    ) {
        int chunkX = packet.getX();
        int chunkZ = packet.getZ();
        if (sableUnavailable || replayingPendingChunkPacket) {
            return false;
        }
        if (isInStorageRange(level, chunkX, chunkZ)) {
            SableLightingDebug.deferDecision(chunkX, chunkZ, "in-storage");
            return false;
        }

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        try {
            if (isSablePlotChunk(level, chunkPos.x, chunkPos.z)) {
                SableLightingDebug.deferDecision(chunkX, chunkZ, "sable-plot");
                return false;
            }

            if (isChunkProtected(level, chunkPos, true)) {
                SableLightingDebug.deferDecision(chunkX, chunkZ, "protected-ready");
                return false;
            }

            RetentionState state = RETAINED_CHUNKS.computeIfAbsent(level, ignored -> new RetentionState());
            state.packetListener = listener;
            long chunkKey = chunkPos.toLong();
            if (!state.pendingChunkPackets.containsKey(chunkKey) && state.pendingChunkPackets.size() >= MAX_PENDING_CHUNK_PACKETS) {
                LongIterator iterator = state.pendingChunkPackets.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.nextLong();
                    iterator.remove();
                }
            }

            state.pendingChunkPackets.put(chunkKey, new PendingChunkPacket(packet, level.getGameTime()));
            SableLightingDebug.deferDecision(chunkX, chunkZ, "queued");
            SableLightingDebug.pendingQueued(chunkPos.x, chunkPos.z, state.pendingChunkPackets.size());
            return true;
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
            return false;
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Sable client chunk retention after pending chunk packet check failed", e);
            sableUnavailable = true;
            return false;
        }
    }

    public static void flushPendingChunkPackets(ClientLevel level) {
        if (sableUnavailable) {
            return;
        }

        RetentionState state = RETAINED_CHUNKS.get(level);
        if (state == null) {
            return;
        }

        try {
            flushPendingChunkPackets(level, state, collectProtectedChunks(level, true));
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Sable client chunk retention after pending chunk flush failed", e);
            sableUnavailable = true;
            releaseAll(level, state);
            RETAINED_CHUNKS.remove(level);
        }
    }

    public static void tick(ClientLevel level) {
        RetentionState state = RETAINED_CHUNKS.get(level);
        if (sableUnavailable) {
            if (state != null) {
                releaseAll(level, state);
                RETAINED_CHUNKS.remove(level);
            }
            return;
        }

        if (state == null) {
            state = new RetentionState();
            RETAINED_CHUNKS.put(level, state);
        }

        long gameTime = level.getGameTime();
        if (gameTime < state.nextSweepGameTime) {
            return;
        }

        state.nextSweepGameTime = gameTime + RETAINED_CHUNK_SWEEP_INTERVAL_TICKS;

        try {
            LongSet protectedChunks = collectProtectedChunks(level, false);
            LongSet readyProtectedChunks = collectProtectedChunks(level, true);
            flushPendingChunkPackets(level, state, readyProtectedChunks);
            prunePendingChunkPackets(state, protectedChunks, gameTime);

            if (protectedChunks.isEmpty()) {
                releaseAll(level, state);
                if (state.pendingChunkPackets.isEmpty()) {
                    RETAINED_CHUNKS.remove(level);
                }
                return;
            }

            pruneShadowChunks(level, state, protectedChunks);
            bootstrapLoadedProtectedChunks(level, state, protectedChunks);

            LongIterator iterator = state.retainedChunks.iterator();
            while (iterator.hasNext()) {
                long chunkKey = iterator.nextLong();
                if (protectedChunks.contains(chunkKey)) {
                    continue;
                }

                releaseChunk(level, new ChunkPos(chunkKey));
                iterator.remove();
                state.bootstrappedChunks.remove(chunkKey);
                clearCachedSkyLight(level, state, chunkKey);
            }

            if (state.retainedChunks.isEmpty()
                    && state.bootstrappedChunks.isEmpty()
                    && state.pendingChunkPackets.isEmpty()
                    && state.shadowSkyLightSections.isEmpty()
                    && copyShadowChunkKeys(level).isEmpty()) {
                RETAINED_CHUNKS.remove(level);
            }
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
            releaseAll(level, state);
            RETAINED_CHUNKS.remove(level);
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Sable client chunk retention after retained chunk sweep failed", e);
            sableUnavailable = true;
            releaseAll(level, state);
            RETAINED_CHUNKS.remove(level);
        }
    }

    public static boolean isChunkRetained(ClientLevel level, ChunkPos chunkPos) {
        RetentionState state = RETAINED_CHUNKS.get(level);
        return state != null && state.retainedChunks.contains(chunkPos.toLong());
    }

    public static void onChunkLightReady(ClientLevel level, LevelChunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        if (!isChunkRetained(level, chunkPos) && !shouldStoreShadowChunk(level, chunkPos)) {
            return;
        }

        boolean ingested = VoxelIngestService.tryAutoIngestChunk(chunk);
        SableLightingDebug.autoIngest("enableChunkLight", chunkPos, ingested);
        if (ingested) {
            RetentionState state = RETAINED_CHUNKS.computeIfAbsent(level, ignored -> new RetentionState());
            state.bootstrappedChunks.add(chunkPos.toLong());
        }
    }

    public static void ingestShadowChunkFromPacket(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) {
        int chunkX = packet.getX();
        int chunkZ = packet.getZ();
        SableLightingDebug.packetSeen(chunkX, chunkZ);

        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            SableLightingDebug.packetNoChunkCache(chunkX, chunkZ);
            return;
        }
        debugClassifyPacket(level, chunkCache, chunkX, chunkZ);

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        if (chunkCache.voxy$isInStorageRange(chunkX, chunkZ)) {
            if (isChunkProtected(level, chunkPos, false)) {
                cacheProtectedChunkLightFromPacket(level, chunkX, chunkZ, packet);
            }
            SableLightingDebug.packetInStorage(chunkX, chunkZ);
            return;
        }

        LevelChunk chunk = chunkCache.voxy$getShadowChunk(chunkX, chunkZ);
        if (chunk == null || chunk.getPos().x != chunkX || chunk.getPos().z != chunkZ) {
            SableLightingDebug.packetNoShadow(chunkX, chunkZ);
            return;
        }

        PacketLightData packetLightData = PacketLightData.from(level, packet.getLightData());
        if (packetLightData == null) {
            SableLightingDebug.packetNoLight(chunkX, chunkZ);
            return;
        }

        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSection();
        RetentionState state = RETAINED_CHUNKS.computeIfAbsent(level, ignored -> new RetentionState());
        int cachedSkySections = cacheSkyLight(state, chunkX, chunkZ, minSection, sections.length, packetLightData);
        SableLightingDebug.packetCachedLight(chunkX, chunkZ, cachedSkySections, packetLightData.countBlockSections());

        WorldIdentifier worldId = WorldIdentifier.of(level);
        if (worldId == null) {
            return;
        }

        boolean ingested = false;
        int sectionAttempts = 0;
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            DataLayer blockLight = packetLightData.blockLight(sectionIndex);
            DataLayer skyLight = packetLightData.skyLight(sectionIndex);
            if (blockLight == null && skyLight == null) {
                continue;
            }
            sectionAttempts++;

            LevelChunkSection section = sections[sectionIndex];
            if (section == null) {
                continue;
            }

            ingested |= VoxelIngestService.rawIngest(
                    worldId,
                    section,
                    chunkX,
                    minSection + sectionIndex,
                    chunkZ,
                    blockLight,
                    skyLight
            );
        }
        SableLightingDebug.packetIngestResult(chunkX, chunkZ, sectionAttempts, ingested);

        if (ingested) {
            state.bootstrappedChunks.add(chunk.getPos().toLong());
        }
    }

    public static int getCachedSubLevelSkyLight(ClientLevel level, Pose3dc pose, @Nullable BoundingBox3dc bounds) {
        if (skyLightFallbackUnavailable) {
            SableLightingDebug.skyLightFallbackUnavailable("disabled");
            return 0;
        }
        if (bounds == null) {
            SableLightingDebug.skyLightFallbackUnavailable("no-bounds");
            return 0;
        }

        try {
            if (bounds.volume() < 9.0D) {
                var position = pose.position();
                int skyLight = getCachedSkyLight(level, position.x(), position.y(), position.z());
                if (skyLight == 0) {
                    skyLight = getCachedSkyLight(level, position.x(), position.y() + 1.0D, position.z());
                }
                if (skyLight == 0) {
                    skyLight = getCachedSkyLight(level, position.x(), position.y() - 1.0D, position.z());
                }
                return skyLight;
            }

            Vector3d center = bounds.center(new Vector3d());
            double sampleY = center.y() + 0.1D;
            int skyLight = getCachedSkyLight(level, center.x(), sampleY, center.z());
            skyLight = Math.max(skyLight, getCachedSkyLight(level, bounds.minX(), sampleY, bounds.minZ()));
            skyLight = Math.max(skyLight, getCachedSkyLight(level, bounds.maxX(), sampleY, bounds.minZ()));
            skyLight = Math.max(skyLight, getCachedSkyLight(level, bounds.minX(), sampleY, bounds.maxZ()));
            skyLight = Math.max(skyLight, getCachedSkyLight(level, bounds.maxX(), sampleY, bounds.maxZ()));
            return skyLight;
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Sable sky light fallback after cached light lookup failed", e);
            skyLightFallbackUnavailable = true;
            return 0;
        }
    }

    public static int getChunkBackedSubLevelSkyLight(ClientLevel level, Pose3dc pose, @Nullable BoundingBox3dc bounds) {
        if (skyLightFallbackUnavailable || bounds == null) {
            return -1;
        }

        try {
            SkyLightSamples samples = new SkyLightSamples();
            if (bounds.volume() < 9.0D) {
                var position = pose.position();
                sampleChunkBackedSkyLight(samples, level, position.x(), position.y(), position.z());
                sampleChunkBackedSkyLight(samples, level, position.x(), position.y() + 1.0D, position.z());
                sampleChunkBackedSkyLight(samples, level, position.x(), position.y() - 1.0D, position.z());
            } else {
                Vector3d center = bounds.center(new Vector3d());
                double sampleY = center.y() + 0.1D;
                sampleChunkBackedSkyLight(samples, level, center.x(), sampleY, center.z());
                sampleChunkBackedSkyLight(samples, level, bounds.minX(), sampleY, bounds.minZ());
                sampleChunkBackedSkyLight(samples, level, bounds.maxX(), sampleY, bounds.minZ());
                sampleChunkBackedSkyLight(samples, level, bounds.minX(), sampleY, bounds.maxZ());
                sampleChunkBackedSkyLight(samples, level, bounds.maxX(), sampleY, bounds.maxZ());
            }

            if (samples.hasSample) {
                return samples.skyLight;
            }
            return -1;
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Sable sky light fallback after chunk-backed light lookup failed", e);
            skyLightFallbackUnavailable = true;
            return -1;
        }
    }

    private static void flushPendingChunkPackets(ClientLevel level, RetentionState state, LongSet readyProtectedChunks) {
        if (state.packetListener == null || state.pendingChunkPackets.isEmpty() || readyProtectedChunks.isEmpty()) {
            return;
        }

        LongSet pendingKeys = new LongOpenHashSet(state.pendingChunkPackets.keySet());
        LongIterator iterator = pendingKeys.iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            if (!readyProtectedChunks.contains(chunkKey)) {
                continue;
            }

            PendingChunkPacket pending = state.pendingChunkPackets.remove(chunkKey);
            if (pending == null || isInStorageRange(level, pending.packet().getX(), pending.packet().getZ())) {
                continue;
            }

            SableLightingDebug.pendingReplayed(pending.packet().getX(), pending.packet().getZ());
            replayingPendingChunkPacket = true;
            try {
                state.packetListener.handleLevelChunkWithLight(pending.packet());
            } finally {
                replayingPendingChunkPacket = false;
            }
        }
    }

    private static void prunePendingChunkPackets(RetentionState state, LongSet protectedChunks, long gameTime) {
        LongIterator iterator = state.pendingChunkPackets.keySet().iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            PendingChunkPacket pending = state.pendingChunkPackets.get(chunkKey);
            if (pending == null) {
                iterator.remove();
                continue;
            }

            if (protectedChunks.contains(chunkKey) && gameTime - pending.queuedAtGameTime() <= PENDING_CHUNK_PACKET_TTL_TICKS) {
                continue;
            }

            if (gameTime - pending.queuedAtGameTime() > PENDING_CHUNK_PACKET_TTL_TICKS) {
                iterator.remove();
            }
        }
    }

    private static void releaseAll(ClientLevel level, RetentionState state) {
        LongIterator iterator = state.retainedChunks.iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            releaseChunk(level, new ChunkPos(chunkKey));
            iterator.remove();
            state.bootstrappedChunks.remove(chunkKey);
        }

        clearShadowChunks(level, state);
        state.bootstrappedChunks.clear();
        state.shadowSkyLightSections.clear();
    }

    private static void releaseChunk(ClientLevel level, ChunkPos chunkPos) {
        removeShadowChunk(level, chunkPos);
        level.getChunkSource().drop(chunkPos);
        level.queueLightUpdate(() -> clearLight(level, chunkPos));
    }

    private static void clearShadowChunks(ClientLevel level, RetentionState state) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return;
        }

        LongIterator iterator = chunkCache.voxy$copyShadowChunkKeys().iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunkKey);
            boolean hasRealChunk = getRealChunk(level, chunkKey) != null;
            if (removeShadowChunk(level, chunkPos) != null && !hasRealChunk) {
                level.queueLightUpdate(() -> clearLight(level, chunkPos));
            }
            state.bootstrappedChunks.remove(chunkKey);
            clearCachedSkyLight(level, state, chunkKey);
        }
    }

    private static void pruneShadowChunks(ClientLevel level, RetentionState state, LongSet protectedChunks) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return;
        }

        LongIterator iterator = chunkCache.voxy$copyShadowChunkKeys().iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            boolean hasRealChunk = getRealChunk(level, chunkKey) != null;
            if (protectedChunks.contains(chunkKey) && !hasRealChunk) {
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(chunkKey);
            if (removeShadowChunk(level, chunkPos) != null && !hasRealChunk) {
                level.queueLightUpdate(() -> clearLight(level, chunkPos));
            }
            state.bootstrappedChunks.remove(chunkKey);
            clearCachedSkyLight(level, state, chunkKey);
        }
    }

    private static void clearLight(ClientLevel level, ChunkPos chunkPos) {
        LevelLightEngine lightEngine = level.getLightEngine();
        lightEngine.setLightEnabled(chunkPos, false);

        for (int sectionY = lightEngine.getMinLightSection(); sectionY < lightEngine.getMaxLightSection(); sectionY++) {
            SectionPos sectionPos = SectionPos.of(chunkPos, sectionY);
            lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, null);
            lightEngine.queueSectionData(LightLayer.SKY, sectionPos, null);
        }

        for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
            lightEngine.updateSectionStatus(SectionPos.of(chunkPos, sectionY), true);
        }
    }

    private static boolean isChunkProtected(ClientLevel level, ChunkPos chunkPos, boolean requireFinalized) {
        LongSet protectedChunks = collectProtectedChunks(level, requireFinalized);
        return protectedChunks.contains(chunkPos.toLong());
    }

    private static LongSet collectProtectedChunks(ClientLevel level, boolean requireFinalized) {
        LongSet protectedChunks = new LongOpenHashSet();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return protectedChunks;
        }

        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }

            if (requireFinalized && !(subLevel instanceof ClientSubLevel clientSubLevel && clientSubLevel.isFinalized())) {
                continue;
            }

            BoundingBox3dc bounds = subLevel.boundingBox();
            if (bounds == null) {
                continue;
            }

            int minChunkX = ((int) Math.floor(bounds.minX()) >> 4) - RETAINED_CHUNK_PADDING;
            int maxChunkX = ((int) Math.floor(bounds.maxX()) >> 4) + RETAINED_CHUNK_PADDING;
            int minChunkZ = ((int) Math.floor(bounds.minZ()) >> 4) - RETAINED_CHUNK_PADDING;
            int maxChunkZ = ((int) Math.floor(bounds.maxZ()) >> 4) + RETAINED_CHUNK_PADDING;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    protectedChunks.add(ChunkPos.asLong(chunkX, chunkZ));
                }
            }
        }

        return protectedChunks;
    }

    private static boolean isSablePlotChunk(ClientLevel level, int chunkX, int chunkZ) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        return container != null && container.inBounds(chunkX, chunkZ);
    }

    private static void bootstrapLoadedProtectedChunks(ClientLevel level, RetentionState state, LongSet protectedChunks) {
        LongIterator iterator = protectedChunks.iterator();
        while (iterator.hasNext()) {
            bootstrapChunkIfLoaded(level, state, iterator.nextLong());
        }
    }

    private static void bootstrapChunkIfLoaded(ClientLevel level, RetentionState state, long chunkKey) {
        LevelChunk chunk = getAvailableChunk(level, chunkKey);
        if (chunk == null || state.bootstrappedChunks.contains(chunkKey)) {
            return;
        }

        boolean ingested = VoxelIngestService.tryAutoIngestChunk(chunk);
        SableLightingDebug.autoIngest("bootstrapLoaded", chunk.getPos(), ingested);
        if (ingested) {
            state.bootstrappedChunks.add(chunkKey);
        }
    }

    private static @Nullable LevelChunk getAvailableChunk(ClientLevel level, long chunkKey) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return null;
        }

        LevelChunk chunk = chunkCache.voxy$cheekyGetChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
        if (chunk != null) {
            return chunk;
        }

        return chunkCache.voxy$getShadowChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
    }

    private static @Nullable LevelChunk getRealChunk(ClientLevel level, long chunkKey) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return null;
        }

        return chunkCache.voxy$cheekyGetChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
    }

    private static @Nullable LevelChunk removeShadowChunk(ClientLevel level, ChunkPos chunkPos) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return null;
        }

        return chunkCache.voxy$removeShadowChunk(chunkPos.x, chunkPos.z);
    }

    private static boolean isInStorageRange(ClientLevel level, int chunkX, int chunkZ) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return true;
        }

        return chunkCache.voxy$isInStorageRange(chunkX, chunkZ);
    }

    private static LongSet copyShadowChunkKeys(ClientLevel level) {
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return new LongOpenHashSet();
        }

        return chunkCache.voxy$copyShadowChunkKeys();
    }

    private static void debugClassifyPacket(ClientLevel level, ICheekyClientChunkCache chunkCache, int chunkX, int chunkZ) {
        if (!SableLightingDebug.enabled()) {
            return;
        }

        LongSet protectedChunks = collectProtectedChunks(level, false);
        LongSet protectedFinalizedChunks = collectProtectedChunks(level, true);
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        SableLightingDebug.packetClassified(
                chunkX,
                chunkZ,
                chunkCache.voxy$isInStorageRange(chunkX, chunkZ),
                isSablePlotChunk(level, chunkX, chunkZ),
                protectedChunks.contains(chunkKey),
                protectedFinalizedChunks.contains(chunkKey),
                chunkCache.voxy$cheekyGetChunk(chunkX, chunkZ) != null,
                chunkCache.voxy$getShadowChunk(chunkX, chunkZ) != null,
                protectedChunks.size(),
                protectedFinalizedChunks.size(),
                chunkCache.voxy$copyShadowChunkKeys().size()
        );
    }

    private static void cacheProtectedChunkLightFromPacket(ClientLevel level, int chunkX, int chunkZ, ClientboundLevelChunkWithLightPacket packet) {
        PacketLightData packetLightData = PacketLightData.from(level, packet.getLightData());
        if (packetLightData == null) {
            SableLightingDebug.packetNoLight(chunkX, chunkZ);
            return;
        }

        RetentionState state = RETAINED_CHUNKS.computeIfAbsent(level, ignored -> new RetentionState());
        int cachedSkySections = cacheSkyLight(
                state,
                chunkX,
                chunkZ,
                level.getMinSection(),
                level.getSectionsCount(),
                packetLightData
        );
        SableLightingDebug.packetCachedLight(chunkX, chunkZ, cachedSkySections, packetLightData.countBlockSections());
    }

    public static String describeSubLevelSkyLightSamples(ClientLevel level, Pose3dc pose, @Nullable BoundingBox3dc bounds) {
        if (!SableLightingDebug.enabled()) {
            return "";
        }

        try {
            if (bounds == null) {
                return "samples unavailable: no bounds";
            }

            LongSet protectedChunks = collectProtectedChunks(level, false);
            LongSet protectedFinalizedChunks = collectProtectedChunks(level, true);
            StringBuilder builder = new StringBuilder();
            builder.append("samples protectedCount=")
                    .append(protectedChunks.size())
                    .append(" protectedFinalizedCount=")
                    .append(protectedFinalizedChunks.size())
                    .append(" ");

            if (bounds.volume() < 9.0D) {
                var position = pose.position();
                appendSkySample(builder, level, protectedChunks, protectedFinalizedChunks, "center", position.x(), position.y(), position.z());
                appendSkySample(builder, level, protectedChunks, protectedFinalizedChunks, "up", position.x(), position.y() + 1.0D, position.z());
                appendSkySample(builder, level, protectedChunks, protectedFinalizedChunks, "down", position.x(), position.y() - 1.0D, position.z());
                return builder.toString();
            }

            Vector3d center = bounds.center(new Vector3d());
            double sampleY = center.y() + 0.1D;
            appendSkySample(builder, level, protectedChunks, protectedFinalizedChunks, "center", center.x(), sampleY, center.z());
            appendSkySample(builder, level, protectedChunks, protectedFinalizedChunks, "minMin", bounds.minX(), sampleY, bounds.minZ());
            appendSkySample(builder, level, protectedChunks, protectedFinalizedChunks, "maxMin", bounds.maxX(), sampleY, bounds.minZ());
            appendSkySample(builder, level, protectedChunks, protectedFinalizedChunks, "minMax", bounds.minX(), sampleY, bounds.maxZ());
            appendSkySample(builder, level, protectedChunks, protectedFinalizedChunks, "maxMax", bounds.maxX(), sampleY, bounds.maxZ());
            return builder.toString();
        } catch (RuntimeException | LinkageError e) {
            return "samples unavailable: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    private static void appendSkySample(
            StringBuilder builder,
            ClientLevel level,
            LongSet protectedChunks,
            LongSet protectedFinalizedChunks,
            String label,
            double x,
            double y,
            double z
    ) {
        BlockPos pos = BlockPos.containing(x, y, z);
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        int vanillaSky = level.getBrightness(LightLayer.SKY, pos);

        boolean hasChunkCache = level.getChunkSource() instanceof ICheekyClientChunkCache;
        boolean inStorage = false;
        boolean hasRealChunk = false;
        boolean hasShadowChunk = false;
        if (hasChunkCache) {
            ICheekyClientChunkCache chunkCache = (ICheekyClientChunkCache) level.getChunkSource();
            inStorage = chunkCache.voxy$isInStorageRange(chunkX, chunkZ);
            hasRealChunk = chunkCache.voxy$cheekyGetChunk(chunkX, chunkZ) != null;
            hasShadowChunk = chunkCache.voxy$getShadowChunk(chunkX, chunkZ) != null;
        }

        RetentionState state = RETAINED_CHUNKS.get(level);
        DataLayer cachedLayer = state == null ? null : state.shadowSkyLightSections.get(SectionPos.asLong(chunkX, sectionY, chunkZ));
        int cachedSky = -1;
        if (cachedLayer != null && !cachedLayer.isEmpty()) {
            cachedSky = Math.min(15, cachedLayer.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
        }
        int voxySky = inStorage && !hasRealChunk ? readVoxySkyLight(level, pos, false) : -1;

        builder.append(label)
                .append("{block=(")
                .append(pos.getX())
                .append(",")
                .append(pos.getY())
                .append(",")
                .append(pos.getZ())
                .append("), chunk=(")
                .append(chunkX)
                .append(",")
                .append(chunkZ)
                .append("), sectionY=")
                .append(sectionY)
                .append(", vanillaSky=")
                .append(vanillaSky)
                .append(", inStorage=")
                .append(inStorage)
                .append(", real=")
                .append(hasRealChunk)
                .append(", shadow=")
                .append(hasShadowChunk)
                .append(", sablePlot=")
                .append(isSablePlotChunk(level, chunkX, chunkZ))
                .append(", protected=")
                .append(protectedChunks.contains(chunkKey))
                .append(", protectedFinalized=")
                .append(protectedFinalizedChunks.contains(chunkKey))
                .append(", cachedLayer=")
                .append(cachedLayer != null)
                .append(", cachedSky=")
                .append(cachedSky)
                .append(", voxySky=")
                .append(voxySky)
                .append("} ");
    }

    private static void sampleChunkBackedSkyLight(SkyLightSamples samples, ClientLevel level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());

        if (level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache
                && chunkCache.voxy$cheekyGetChunk(chunkX, chunkZ) != null) {
            samples.add(level.getBrightness(LightLayer.SKY, pos));
            return;
        } else if (level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache
                && chunkCache.voxy$isInStorageRange(chunkX, chunkZ)) {
            int voxySkyLight = readVoxySkyLight(level, pos, true);
            if (voxySkyLight >= 0) {
                samples.add(voxySkyLight);
            }
        }

        int cachedSkyLight = getCachedSkyLight(level, pos);
        if (cachedSkyLight >= 0) {
            samples.add(cachedSkyLight);
        }
    }

    private static int readVoxySkyLight(ClientLevel level, BlockPos pos, boolean countDebug) {
        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
        if (sectionY < level.getMinSection() || sectionY >= level.getMaxSection()) {
            if (countDebug) {
                SableLightingDebug.voxySkySampleRejected("out-of-y");
            }
            return -1;
        }

        WorldEngine engine = WorldIdentifier.ofEngineNullable(level);
        if (engine == null) {
            if (countDebug) {
                SableLightingDebug.voxySkySampleRejected("no-world");
            }
            return -1;
        }

        int sectionX = SectionPos.blockToSectionCoord(pos.getX());
        int sectionZ = SectionPos.blockToSectionCoord(pos.getZ());
        WorldSection section = engine.acquireIfExists(0, sectionX >> 1, sectionY >> 1, sectionZ >> 1);
        if (section == null) {
            if (countDebug) {
                SableLightingDebug.voxySkySampleRejected("no-section");
            }
            return -1;
        }

        try {
            int index = WorldSection.getIndex(pos.getX() & 31, pos.getY() & 31, pos.getZ() & 31);
            int skyLight = Mapper.getLightId(section._unsafeGetRawDataArray()[index]) & 15;
            if (countDebug) {
                SableLightingDebug.voxySkySampleHit(skyLight);
            }
            return skyLight;
        } finally {
            section.release();
        }
    }

    private static int cacheSkyLight(
            RetentionState state,
            int chunkX,
            int chunkZ,
            int minSection,
            int sectionCount,
            PacketLightData packetLightData
    ) {
        int cachedSections = 0;
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            DataLayer skyLight = packetLightData.skyLight(sectionIndex);
            if (skyLight == null) {
                continue;
            }

            state.shadowSkyLightSections.put(
                    SectionPos.asLong(chunkX, minSection + sectionIndex, chunkZ),
                    skyLight.copy()
            );
            cachedSections++;
        }
        return cachedSections;
    }

    private static void clearCachedSkyLight(ClientLevel level, RetentionState state, long chunkKey) {
        int chunkX = ChunkPos.getX(chunkKey);
        int chunkZ = ChunkPos.getZ(chunkKey);
        for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
            state.shadowSkyLightSections.remove(SectionPos.asLong(chunkX, sectionY, chunkZ));
        }
    }

    private static int getCachedSkyLight(ClientLevel level, double x, double y, double z) {
        return Math.max(0, getCachedSkyLight(level, BlockPos.containing(x, y, z)));
    }

    private static int getCachedSkyLight(ClientLevel level, BlockPos pos) {
        RetentionState state = RETAINED_CHUNKS.get(level);
        if (state == null || state.shadowSkyLightSections.isEmpty()) {
            SableLightingDebug.cacheSampleRejected("no-state");
            return -1;
        }

        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
        if (sectionY < level.getMinSection() || sectionY >= level.getMaxSection()) {
            SableLightingDebug.cacheSampleRejected("out-of-y");
            return -1;
        }

        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache)) {
            SableLightingDebug.cacheSampleRejected("no-chunk-cache");
            return -1;
        }

        DataLayer skyLight = state.shadowSkyLightSections.get(SectionPos.asLong(chunkX, sectionY, chunkZ));
        if (skyLight == null) {
            SableLightingDebug.cacheSampleRejected("no-layer");
            return -1;
        }
        if (skyLight.isEmpty()) {
            SableLightingDebug.cacheSampleRejected("empty-layer");
            return 0;
        }

        int cachedSkyLight = Math.min(15, skyLight.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
        SableLightingDebug.cacheSampleHit(cachedSkyLight);
        return cachedSkyLight;
    }

    private static final class SkyLightSamples {
        private boolean hasSample;
        private int skyLight;

        private void add(int skyLight) {
            this.hasSample = true;
            this.skyLight = Math.max(this.skyLight, skyLight);
        }
    }

    private static final class RetentionState {
        private final LongSet retainedChunks = new LongOpenHashSet();
        private final LongSet bootstrappedChunks = new LongOpenHashSet();
        private final Long2ObjectMap<PendingChunkPacket> pendingChunkPackets = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectMap<DataLayer> shadowSkyLightSections = new Long2ObjectOpenHashMap<>();
        private ClientPacketListener packetListener;
        private long nextSweepGameTime;
    }

    private record PendingChunkPacket(ClientboundLevelChunkWithLightPacket packet, long queuedAtGameTime) {
    }

    private record PacketLightData(DataLayer[] blockLight, DataLayer[] skyLight) {
        private static @Nullable PacketLightData from(ClientLevel level, ClientboundLightUpdatePacketData lightData) {
            int sectionCount = level.getSectionsCount();
            DataLayer[] blockLight = new DataLayer[sectionCount];
            DataLayer[] skyLight = new DataLayer[sectionCount];

            boolean hasBlockLight = unpackLayer(
                    level,
                    lightData.getBlockYMask(),
                    lightData.getEmptyBlockYMask(),
                    lightData.getBlockUpdates(),
                    blockLight
            );
            boolean hasSkyLight = unpackLayer(
                    level,
                    lightData.getSkyYMask(),
                    lightData.getEmptySkyYMask(),
                    lightData.getSkyUpdates(),
                    skyLight
            );

            if (!hasBlockLight && !hasSkyLight) {
                return null;
            }

            return new PacketLightData(blockLight, skyLight);
        }

        private static boolean unpackLayer(ClientLevel level, BitSet dataMask, BitSet emptyMask, List<byte[]> updates, DataLayer[] target) {
            LevelLightEngine lightEngine = level.getLightEngine();
            int minLightSection = lightEngine.getMinLightSection();
            int lightSectionCount = lightEngine.getLightSectionCount();
            int minChunkSection = level.getMinSection();
            boolean foundData = false;
            Iterator<byte[]> iterator = updates.iterator();

            for (int lightSectionIndex = 0; lightSectionIndex < lightSectionCount; lightSectionIndex++) {
                int sectionY = minLightSection + lightSectionIndex;
                boolean hasData = dataMask.get(lightSectionIndex);
                boolean empty = emptyMask.get(lightSectionIndex);
                if (!hasData && !empty) {
                    continue;
                }

                DataLayer layer;
                if (hasData) {
                    if (!iterator.hasNext()) {
                        return foundData;
                    }
                    layer = new DataLayer(iterator.next().clone());
                } else {
                    layer = new DataLayer();
                }

                int sectionIndex = sectionY - minChunkSection;
                if (sectionIndex < 0 || sectionIndex >= target.length) {
                    continue;
                }

                target[sectionIndex] = layer;
                foundData = true;
            }

            return foundData;
        }

        private @Nullable DataLayer blockLight(int sectionIndex) {
            return this.blockLight[sectionIndex];
        }

        private @Nullable DataLayer skyLight(int sectionIndex) {
            return this.skyLight[sectionIndex];
        }

        private int countBlockSections() {
            return countSections(this.blockLight);
        }

        private static int countSections(DataLayer[] layers) {
            int count = 0;
            for (DataLayer layer : layers) {
                if (layer != null) {
                    count++;
                }
            }
            return count;
        }
    }
}
