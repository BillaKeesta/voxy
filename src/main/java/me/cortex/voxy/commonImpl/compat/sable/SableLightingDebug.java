package me.cortex.voxy.commonImpl.compat.sable;

import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.joml.Vector3dc;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class SableLightingDebug {
    public static final boolean ENABLED = flag("voxy.debug")
            || flag("voxy.sableLightingDebug")
            || flag("voxy.debug.sableLighting");

    private static final long SUMMARY_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5L);
    private static final long SAMPLE_LIMIT = 80L;

    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();
    private static final AtomicLong NEXT_SUMMARY_NS = new AtomicLong();
    private static final ConcurrentMap<String, AtomicLong> SAMPLE_LOGS = new ConcurrentHashMap<>();

    private static final AtomicLong PACKETS_SEEN = new AtomicLong();
    private static final AtomicLong PACKETS_IN_STORAGE = new AtomicLong();
    private static final AtomicLong PACKETS_CLASSIFIED_IN_STORAGE = new AtomicLong();
    private static final AtomicLong PACKETS_CLASSIFIED_OUT_OF_STORAGE = new AtomicLong();
    private static final AtomicLong PACKETS_CLASSIFIED_SABLE_PLOT = new AtomicLong();
    private static final AtomicLong PACKETS_CLASSIFIED_PROTECTED = new AtomicLong();
    private static final AtomicLong PACKETS_CLASSIFIED_PROTECTED_FINALIZED = new AtomicLong();
    private static final AtomicLong PACKETS_CLASSIFIED_REAL_CHUNK = new AtomicLong();
    private static final AtomicLong PACKETS_CLASSIFIED_SHADOW_CHUNK = new AtomicLong();
    private static final AtomicLong PACKETS_CLASSIFIED_UNRELATED = new AtomicLong();
    private static final AtomicLong PACKETS_NO_CHUNK_CACHE = new AtomicLong();
    private static final AtomicLong PACKETS_NO_SHADOW = new AtomicLong();
    private static final AtomicLong PACKETS_NO_LIGHT = new AtomicLong();
    private static final AtomicLong PACKETS_CACHED = new AtomicLong();
    private static final AtomicLong PACKET_SKY_SECTIONS_CACHED = new AtomicLong();
    private static final AtomicLong PACKET_BLOCK_SECTIONS = new AtomicLong();
    private static final AtomicLong PACKET_INGEST_ATTEMPTS = new AtomicLong();
    private static final AtomicLong PACKET_INGESTED_CHUNKS = new AtomicLong();

    private static final AtomicLong DEFER_CHECKS = new AtomicLong();
    private static final AtomicLong DEFER_IN_STORAGE = new AtomicLong();
    private static final AtomicLong DEFER_SABLE_PLOT = new AtomicLong();
    private static final AtomicLong DEFER_PROTECTED_READY = new AtomicLong();
    private static final AtomicLong DEFER_QUEUED = new AtomicLong();

    private static final AtomicLong SHADOW_STORE_CHECKS = new AtomicLong();
    private static final AtomicLong SHADOW_STORE_REJECT_PLOT = new AtomicLong();
    private static final AtomicLong SHADOW_STORE_REJECT_NOT_PROTECTED = new AtomicLong();
    private static final AtomicLong SHADOW_STORE_ALLOWED = new AtomicLong();
    private static final AtomicLong SHADOW_STORE_STORED = new AtomicLong();
    private static final AtomicLong SHADOW_STORE_REUSED = new AtomicLong();

    private static final AtomicLong PENDING_PACKETS_QUEUED = new AtomicLong();
    private static final AtomicLong PENDING_PACKETS_REPLAYED = new AtomicLong();

    private static final AtomicLong AUTO_INGEST_ATTEMPTS = new AtomicLong();
    private static final AtomicLong AUTO_INGEST_SUCCESSES = new AtomicLong();

    private static final AtomicLong SUBLEVEL_FINALIZED = new AtomicLong();
    private static final AtomicLong SKY_SCALE_COMPUTES = new AtomicLong();
    private static final AtomicLong SKY_SCALE_VANILLA_POSITIVE = new AtomicLong();
    private static final AtomicLong SKY_SCALE_FALLBACK_ATTEMPTS = new AtomicLong();
    private static final AtomicLong SKY_SCALE_FALLBACK_SUCCESSES = new AtomicLong();
    private static final AtomicLong SKY_SCALE_FALLBACK_FAILURES = new AtomicLong();
    private static final AtomicLong SKY_SCALE_FALLBACK_UNAVAILABLE = new AtomicLong();
    private static final AtomicLong SKY_SCALE_ZERO_CONTEXTS = new AtomicLong();

    private static final AtomicLong CACHE_SAMPLE_HITS = new AtomicLong();
    private static final AtomicLong CACHE_SAMPLE_NO_STATE = new AtomicLong();
    private static final AtomicLong CACHE_SAMPLE_OUT_OF_Y = new AtomicLong();
    private static final AtomicLong CACHE_SAMPLE_NO_CHUNK_CACHE = new AtomicLong();
    private static final AtomicLong CACHE_SAMPLE_IN_STORAGE = new AtomicLong();
    private static final AtomicLong CACHE_SAMPLE_NO_SHADOW = new AtomicLong();
    private static final AtomicLong CACHE_SAMPLE_NO_LAYER = new AtomicLong();
    private static final AtomicLong CACHE_SAMPLE_EMPTY_LAYER = new AtomicLong();
    private static final AtomicLong VOXY_SKY_SAMPLE_HITS = new AtomicLong();
    private static final AtomicLong VOXY_SKY_SAMPLE_NO_WORLD = new AtomicLong();
    private static final AtomicLong VOXY_SKY_SAMPLE_OUT_OF_Y = new AtomicLong();
    private static final AtomicLong VOXY_SKY_SAMPLE_NO_SECTION = new AtomicLong();

    private static final AtomicLong FLYWHEEL_RENDER_INFOS = new AtomicLong();
    private static final AtomicLong FLYWHEEL_FRAMES = new AtomicLong();
    private static final AtomicLong FLYWHEEL_UPDATES = new AtomicLong();
    private static final AtomicLong FLYWHEEL_ZERO_SKY_UPDATES = new AtomicLong();
    private static final AtomicLong FLYWHEEL_POSITIVE_SKY_UPDATES = new AtomicLong();

    private static final AtomicLong LATEST_SKY_READS = new AtomicLong();
    private static final AtomicLong LATEST_SKY_ZERO_READS = new AtomicLong();
    private static final AtomicLong LATEST_SKY_POSITIVE_READS = new AtomicLong();

    private static final AtomicLong SODIUM_UPDATE_CHUNKS = new AtomicLong();
    private static final AtomicLong SODIUM_SET_DIRTY = new AtomicLong();

    private SableLightingDebug() {
    }

    public static boolean enabled() {
        if (ENABLED) {
            announce();
        }
        return ENABLED;
    }

    public static void packetSeen(int chunkX, int chunkZ) {
        if (!enabled()) {
            return;
        }
        PACKETS_SEEN.incrementAndGet();
        sample("packet", "seen " + chunk(chunkX, chunkZ));
        summary("packet");
    }

    public static void packetClassified(
            int chunkX,
            int chunkZ,
            boolean inStorage,
            boolean sablePlot,
            boolean protectedChunk,
            boolean protectedFinalizedChunk,
            boolean hasRealChunk,
            boolean hasShadowChunk,
            int protectedChunkCount,
            int protectedFinalizedChunkCount,
            int shadowChunkCount
    ) {
        if (!enabled()) {
            return;
        }
        if (inStorage) {
            PACKETS_CLASSIFIED_IN_STORAGE.incrementAndGet();
        } else {
            PACKETS_CLASSIFIED_OUT_OF_STORAGE.incrementAndGet();
        }
        if (sablePlot) {
            PACKETS_CLASSIFIED_SABLE_PLOT.incrementAndGet();
        }
        if (protectedChunk) {
            PACKETS_CLASSIFIED_PROTECTED.incrementAndGet();
        }
        if (protectedFinalizedChunk) {
            PACKETS_CLASSIFIED_PROTECTED_FINALIZED.incrementAndGet();
        }
        if (hasRealChunk) {
            PACKETS_CLASSIFIED_REAL_CHUNK.incrementAndGet();
        }
        if (hasShadowChunk) {
            PACKETS_CLASSIFIED_SHADOW_CHUNK.incrementAndGet();
        }
        if (!inStorage && !sablePlot && !protectedChunk && !hasShadowChunk) {
            PACKETS_CLASSIFIED_UNRELATED.incrementAndGet();
        }
        sample(
                "packet-classify",
                chunk(chunkX, chunkZ)
                        + " inStorage=" + inStorage
                        + " sablePlot=" + sablePlot
                        + " protected=" + protectedChunk
                        + " protectedFinalized=" + protectedFinalizedChunk
                        + " real=" + hasRealChunk
                        + " shadow=" + hasShadowChunk
                        + " protectedCount=" + protectedChunkCount
                        + " protectedFinalizedCount=" + protectedFinalizedChunkCount
                        + " shadowCount=" + shadowChunkCount
        );
        summary("packet-classified");
    }

    public static void packetNoChunkCache(int chunkX, int chunkZ) {
        if (!enabled()) {
            return;
        }
        PACKETS_NO_CHUNK_CACHE.incrementAndGet();
        sample("packet", "ignored no-cheeky-cache " + chunk(chunkX, chunkZ));
        summary("packet-no-cache");
    }

    public static void packetInStorage(int chunkX, int chunkZ) {
        if (!enabled()) {
            return;
        }
        PACKETS_IN_STORAGE.incrementAndGet();
        summary("packet-in-storage");
    }

    public static void packetNoShadow(int chunkX, int chunkZ) {
        if (!enabled()) {
            return;
        }
        PACKETS_NO_SHADOW.incrementAndGet();
        sample("packet", "ignored no-shadow-chunk " + chunk(chunkX, chunkZ));
        summary("packet-no-shadow");
    }

    public static void packetNoLight(int chunkX, int chunkZ) {
        if (!enabled()) {
            return;
        }
        PACKETS_NO_LIGHT.incrementAndGet();
        sample("packet", "ignored no-light-data " + chunk(chunkX, chunkZ));
        summary("packet-no-light");
    }

    public static void packetCachedLight(int chunkX, int chunkZ, int skySections, int blockSections) {
        if (!enabled()) {
            return;
        }
        PACKETS_CACHED.incrementAndGet();
        PACKET_SKY_SECTIONS_CACHED.addAndGet(skySections);
        PACKET_BLOCK_SECTIONS.addAndGet(blockSections);
        sample("packet", "cached " + chunk(chunkX, chunkZ) + " skySections=" + skySections + " blockSections=" + blockSections);
        summary("packet-cached");
    }

    public static void packetIngestResult(int chunkX, int chunkZ, int sectionAttempts, boolean ingested) {
        if (!enabled()) {
            return;
        }
        PACKET_INGEST_ATTEMPTS.addAndGet(sectionAttempts);
        if (ingested) {
            PACKET_INGESTED_CHUNKS.incrementAndGet();
        }
        sample("packet", "ingest " + chunk(chunkX, chunkZ) + " sections=" + sectionAttempts + " ingested=" + ingested);
        summary("packet-ingest");
    }

    public static void deferDecision(int chunkX, int chunkZ, String reason) {
        if (!enabled()) {
            return;
        }
        DEFER_CHECKS.incrementAndGet();
        switch (reason) {
            case "in-storage" -> DEFER_IN_STORAGE.incrementAndGet();
            case "sable-plot" -> DEFER_SABLE_PLOT.incrementAndGet();
            case "protected-ready" -> DEFER_PROTECTED_READY.incrementAndGet();
            case "queued" -> DEFER_QUEUED.incrementAndGet();
            default -> {
            }
        }
        sample("defer", chunk(chunkX, chunkZ) + " reason=" + reason);
        summary("defer-" + reason);
    }

    public static void shadowStoreDecision(int chunkX, int chunkZ, String reason) {
        if (!enabled()) {
            return;
        }
        SHADOW_STORE_CHECKS.incrementAndGet();
        switch (reason) {
            case "sable-plot" -> SHADOW_STORE_REJECT_PLOT.incrementAndGet();
            case "not-protected-finalized" -> SHADOW_STORE_REJECT_NOT_PROTECTED.incrementAndGet();
            case "allowed" -> SHADOW_STORE_ALLOWED.incrementAndGet();
            default -> {
            }
        }
        sample("shadow-store", chunk(chunkX, chunkZ) + " decision=" + reason);
        summary("shadow-store-" + reason);
    }

    public static void shadowStored(int chunkX, int chunkZ, boolean reused) {
        if (!enabled()) {
            return;
        }
        SHADOW_STORE_STORED.incrementAndGet();
        if (reused) {
            SHADOW_STORE_REUSED.incrementAndGet();
        }
        sample("shadow-store", "stored " + chunk(chunkX, chunkZ) + " reused=" + reused);
        summary("shadow-stored");
    }

    public static void pendingQueued(int chunkX, int chunkZ, int pendingCount) {
        if (!enabled()) {
            return;
        }
        PENDING_PACKETS_QUEUED.incrementAndGet();
        sample("pending", "queued " + chunk(chunkX, chunkZ) + " pending=" + pendingCount);
        summary("pending-queued");
    }

    public static void pendingReplayed(int chunkX, int chunkZ) {
        if (!enabled()) {
            return;
        }
        PENDING_PACKETS_REPLAYED.incrementAndGet();
        sample("pending", "replayed " + chunk(chunkX, chunkZ));
        summary("pending-replayed");
    }

    public static void autoIngest(String source, ChunkPos pos, boolean ingested) {
        if (!enabled()) {
            return;
        }
        AUTO_INGEST_ATTEMPTS.incrementAndGet();
        if (ingested) {
            AUTO_INGEST_SUCCESSES.incrementAndGet();
        }
        sample("auto-ingest", source + " " + chunk(pos.x, pos.z) + " ingested=" + ingested);
        summary("auto-ingest");
    }

    public static void subLevelFinalized(ClientLevel level) {
        if (!enabled()) {
            return;
        }
        SUBLEVEL_FINALIZED.incrementAndGet();
        sample("sublevel", "finalized worldTime=" + level.getGameTime());
        summary("sublevel-finalized");
    }

    public static void skyLightFallbackUnavailable(String reason) {
        if (!enabled()) {
            return;
        }
        SKY_SCALE_FALLBACK_UNAVAILABLE.incrementAndGet();
        sample("sky-scale", "fallback unavailable reason=" + reason);
        summary("fallback-unavailable");
    }

    public static void skyLightDecision(int vanillaSkyLight, int fallbackSkyLight, boolean attemptedFallback, Pose3dc pose, BoundingBox3dc bounds) {
        if (!enabled()) {
            return;
        }
        SKY_SCALE_COMPUTES.incrementAndGet();
        if (vanillaSkyLight > 0) {
            SKY_SCALE_VANILLA_POSITIVE.incrementAndGet();
        }
        if (attemptedFallback) {
            SKY_SCALE_FALLBACK_ATTEMPTS.incrementAndGet();
            if (fallbackSkyLight > 0) {
                SKY_SCALE_FALLBACK_SUCCESSES.incrementAndGet();
            } else {
                SKY_SCALE_FALLBACK_FAILURES.incrementAndGet();
            }
        }
        sample(
                "sky-scale",
                "vanilla=" + vanillaSkyLight
                        + " fallback=" + fallbackSkyLight
                        + " attempted=" + attemptedFallback
                        + " pos=" + poseSummary(pose)
                        + " bounds=" + boundsSummary(bounds)
        );
        summary("sky-scale");
    }

    public static void skyLightZeroContext(String context) {
        if (!enabled()) {
            return;
        }
        SKY_SCALE_ZERO_CONTEXTS.incrementAndGet();
        sample("sky-zero-context", context);
        summary("sky-zero-context");
    }

    public static void cacheSampleRejected(String reason) {
        if (!enabled()) {
            return;
        }
        switch (reason) {
            case "no-state" -> CACHE_SAMPLE_NO_STATE.incrementAndGet();
            case "out-of-y" -> CACHE_SAMPLE_OUT_OF_Y.incrementAndGet();
            case "no-chunk-cache" -> CACHE_SAMPLE_NO_CHUNK_CACHE.incrementAndGet();
            case "in-storage" -> CACHE_SAMPLE_IN_STORAGE.incrementAndGet();
            case "no-shadow" -> CACHE_SAMPLE_NO_SHADOW.incrementAndGet();
            case "no-layer" -> CACHE_SAMPLE_NO_LAYER.incrementAndGet();
            case "empty-layer" -> CACHE_SAMPLE_EMPTY_LAYER.incrementAndGet();
            default -> {
            }
        }
        summary("cache-sample-" + reason);
    }

    public static void cacheSampleHit(int skyLight) {
        if (!enabled()) {
            return;
        }
        CACHE_SAMPLE_HITS.incrementAndGet();
        sample("cache-sample", "hit sky=" + skyLight);
        summary("cache-hit");
    }

    public static void voxySkySampleRejected(String reason) {
        if (!enabled()) {
            return;
        }
        switch (reason) {
            case "no-world" -> VOXY_SKY_SAMPLE_NO_WORLD.incrementAndGet();
            case "out-of-y" -> VOXY_SKY_SAMPLE_OUT_OF_Y.incrementAndGet();
            case "no-section" -> VOXY_SKY_SAMPLE_NO_SECTION.incrementAndGet();
            default -> {
            }
        }
        summary("voxy-sky-" + reason);
    }

    public static void voxySkySampleHit(int skyLight) {
        if (!enabled()) {
            return;
        }
        VOXY_SKY_SAMPLE_HITS.incrementAndGet();
        sample("voxy-sky", "hit sky=" + skyLight);
        summary("voxy-sky-hit");
    }

    public static void flywheelRenderInfo(Level level, SubLevel subLevel) {
        if (!enabled()) {
            return;
        }
        FLYWHEEL_RENDER_INFOS.incrementAndGet();
        sample("flywheel", "createRenderInfo dim=" + level.dimension().location() + " subLevel=" + subLevel);
        summary("flywheel-render-info");
    }

    public static void flywheelFrame() {
        if (!enabled()) {
            return;
        }
        FLYWHEEL_FRAMES.incrementAndGet();
        summary("flywheel-frame");
    }

    public static void latestSkyLightScaleRead(ClientSubLevel subLevel, int skyLight) {
        if (!enabled()) {
            return;
        }
        LATEST_SKY_READS.incrementAndGet();
        if (skyLight > 0) {
            LATEST_SKY_POSITIVE_READS.incrementAndGet();
        } else {
            LATEST_SKY_ZERO_READS.incrementAndGet();
        }
        sample("client-sublevel", "getLatestSkyLightScale sky=" + skyLight + " scene=" + subLevel.getLightingSceneId() + " subLevel=" + subLevel);
        summary("latest-sky-read");
    }

    public static void flywheelUpdate(ClientSubLevel subLevel) {
        if (!enabled()) {
            return;
        }
        int skyLight = subLevel.getLatestSkyLightScale();
        FLYWHEEL_UPDATES.incrementAndGet();
        if (skyLight > 0) {
            FLYWHEEL_POSITIVE_SKY_UPDATES.incrementAndGet();
        } else {
            FLYWHEEL_ZERO_SKY_UPDATES.incrementAndGet();
        }
        sample("flywheel", "update sky=" + skyLight + " scene=" + subLevel.getLightingSceneId() + " subLevel=" + subLevel);
        summary("flywheel-update");
    }

    public static void sodiumUpdateChunks(boolean important) {
        if (!enabled()) {
            return;
        }
        SODIUM_UPDATE_CHUNKS.incrementAndGet();
        sample("sable-sodium", "updateChunks important=" + important);
        summary("sodium-update-chunks");
    }

    public static void sodiumSetDirty(int x, int y, int z, boolean important) {
        if (!enabled()) {
            return;
        }
        SODIUM_SET_DIRTY.incrementAndGet();
        sample("sable-sodium", "setDirty section=(" + x + ", " + y + ", " + z + ") important=" + important);
        summary("sodium-set-dirty");
    }

    private static boolean flag(String name) {
        String value = System.getProperty(name);
        return value != null
                && (value.isEmpty()
                || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("1")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("on"));
    }

    private static void announce() {
        if (ANNOUNCED.compareAndSet(false, true)) {
            Logger.info("Sable lighting debug enabled; use -Dvoxy.debug=true or -Dvoxy.sableLightingDebug=true");
        }
    }

    private static void sample(String category, String message) {
        long index = SAMPLE_LOGS.computeIfAbsent(category, ignored -> new AtomicLong()).getAndIncrement();
        if (index < SAMPLE_LIMIT) {
            Logger.info("[SableLightingDebug] " + category + " " + message);
        }
    }

    private static void summary(String reason) {
        long now = System.nanoTime();
        long next = NEXT_SUMMARY_NS.get();
        if (now < next || !NEXT_SUMMARY_NS.compareAndSet(next, now + SUMMARY_INTERVAL_NS)) {
            return;
        }

        Logger.info("[SableLightingDebug] summary reason=" + reason
                + " packets(seen=" + PACKETS_SEEN.get()
                + ", inStorage=" + PACKETS_IN_STORAGE.get()
                + ", classifiedInStorage=" + PACKETS_CLASSIFIED_IN_STORAGE.get()
                + ", classifiedOutOfStorage=" + PACKETS_CLASSIFIED_OUT_OF_STORAGE.get()
                + ", sablePlot=" + PACKETS_CLASSIFIED_SABLE_PLOT.get()
                + ", protected=" + PACKETS_CLASSIFIED_PROTECTED.get()
                + ", protectedFinalized=" + PACKETS_CLASSIFIED_PROTECTED_FINALIZED.get()
                + ", realChunk=" + PACKETS_CLASSIFIED_REAL_CHUNK.get()
                + ", shadowChunk=" + PACKETS_CLASSIFIED_SHADOW_CHUNK.get()
                + ", unrelatedOutOfStorage=" + PACKETS_CLASSIFIED_UNRELATED.get()
                + ", noCache=" + PACKETS_NO_CHUNK_CACHE.get()
                + ", noShadow=" + PACKETS_NO_SHADOW.get()
                + ", noLight=" + PACKETS_NO_LIGHT.get()
                + ", cached=" + PACKETS_CACHED.get()
                + ", skySections=" + PACKET_SKY_SECTIONS_CACHED.get()
                + ", blockSections=" + PACKET_BLOCK_SECTIONS.get()
                + ", ingestSectionAttempts=" + PACKET_INGEST_ATTEMPTS.get()
                + ", ingestedChunks=" + PACKET_INGESTED_CHUNKS.get()
                + ")");
        Logger.info("[SableLightingDebug] summary retention(deferChecks=" + DEFER_CHECKS.get()
                + ", deferInStorage=" + DEFER_IN_STORAGE.get()
                + ", deferSablePlot=" + DEFER_SABLE_PLOT.get()
                + ", deferProtectedReady=" + DEFER_PROTECTED_READY.get()
                + ", deferQueued=" + DEFER_QUEUED.get()
                + ", shadowChecks=" + SHADOW_STORE_CHECKS.get()
                + ", shadowRejectPlot=" + SHADOW_STORE_REJECT_PLOT.get()
                + ", shadowRejectNotProtected=" + SHADOW_STORE_REJECT_NOT_PROTECTED.get()
                + ", shadowAllowed=" + SHADOW_STORE_ALLOWED.get()
                + ", shadowStored=" + SHADOW_STORE_STORED.get()
                + ", shadowReused=" + SHADOW_STORE_REUSED.get()
                + ", pendingQueued=" + PENDING_PACKETS_QUEUED.get()
                + ", pendingReplayed=" + PENDING_PACKETS_REPLAYED.get()
                + ")");
        Logger.info("[SableLightingDebug] summary skyScale(computes=" + SKY_SCALE_COMPUTES.get()
                + ", vanillaPositive=" + SKY_SCALE_VANILLA_POSITIVE.get()
                + ", fallbackAttempts=" + SKY_SCALE_FALLBACK_ATTEMPTS.get()
                + ", fallbackSuccess=" + SKY_SCALE_FALLBACK_SUCCESSES.get()
                + ", fallbackFail=" + SKY_SCALE_FALLBACK_FAILURES.get()
                + ", fallbackUnavailable=" + SKY_SCALE_FALLBACK_UNAVAILABLE.get()
                + ", zeroContexts=" + SKY_SCALE_ZERO_CONTEXTS.get()
                + ", cacheHits=" + CACHE_SAMPLE_HITS.get()
                + ", noState=" + CACHE_SAMPLE_NO_STATE.get()
                + ", outOfY=" + CACHE_SAMPLE_OUT_OF_Y.get()
                + ", noChunkCache=" + CACHE_SAMPLE_NO_CHUNK_CACHE.get()
                + ", inStorage=" + CACHE_SAMPLE_IN_STORAGE.get()
                + ", noShadow=" + CACHE_SAMPLE_NO_SHADOW.get()
                + ", noLayer=" + CACHE_SAMPLE_NO_LAYER.get()
                + ", emptyLayer=" + CACHE_SAMPLE_EMPTY_LAYER.get()
                + ", voxyHits=" + VOXY_SKY_SAMPLE_HITS.get()
                + ", voxyNoWorld=" + VOXY_SKY_SAMPLE_NO_WORLD.get()
                + ", voxyOutOfY=" + VOXY_SKY_SAMPLE_OUT_OF_Y.get()
                + ", voxyNoSection=" + VOXY_SKY_SAMPLE_NO_SECTION.get()
                + ")");
        Logger.info("[SableLightingDebug] summary render(subLevelsFinalized=" + SUBLEVEL_FINALIZED.get()
                + ", autoIngestAttempts=" + AUTO_INGEST_ATTEMPTS.get()
                + ", autoIngestSuccess=" + AUTO_INGEST_SUCCESSES.get()
                + ", flywheelRenderInfos=" + FLYWHEEL_RENDER_INFOS.get()
                + ", flywheelFrames=" + FLYWHEEL_FRAMES.get()
                + ", flywheelUpdates=" + FLYWHEEL_UPDATES.get()
                + ", flywheelZeroSky=" + FLYWHEEL_ZERO_SKY_UPDATES.get()
                + ", flywheelPositiveSky=" + FLYWHEEL_POSITIVE_SKY_UPDATES.get()
                + ", latestSkyReads=" + LATEST_SKY_READS.get()
                + ", latestSkyZero=" + LATEST_SKY_ZERO_READS.get()
                + ", latestSkyPositive=" + LATEST_SKY_POSITIVE_READS.get()
                + ", sodiumUpdateChunks=" + SODIUM_UPDATE_CHUNKS.get()
                + ", sodiumSetDirty=" + SODIUM_SET_DIRTY.get()
                + ")");
    }

    private static String chunk(int chunkX, int chunkZ) {
        return "chunk=(" + chunkX + ", " + chunkZ + ")";
    }

    private static String poseSummary(Pose3dc pose) {
        if (pose == null) {
            return "null";
        }
        Vector3dc position = pose.position();
        return vectorSummary(position);
    }

    private static String vectorSummary(Vector3dc vector) {
        if (vector == null) {
            return "null";
        }
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", vector.x(), vector.y(), vector.z());
    }

    private static String boundsSummary(BoundingBox3dc bounds) {
        if (bounds == null) {
            return "null";
        }
        return String.format(
                Locale.ROOT,
                "[(%.2f, %.2f, %.2f)..(%.2f, %.2f, %.2f)]",
                bounds.minX(),
                bounds.minY(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxY(),
                bounds.maxZ()
        );
    }
}
