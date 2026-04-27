package me.cortex.voxy.commonImpl.compat.sable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.mixin.sable.SableSubLevelHoldingChunkMapAccessor;
import me.cortex.voxy.commonImpl.mixin.sable.SableSubLevelStorageAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SableLodChunkManager {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("voxy-config.json");
    private static final TicketType<ChunkPos> VOXY_SABLE_LOD_TICKET = TicketType.create("voxy_sable_lod", Comparator.comparingLong(ChunkPos::toLong));
    private static final int TICKET_DISTANCE = 2;
    private static final int CONFIG_REFRESH_TICKS = 20;
    private static final int HOLDING_INDEX_REFRESH_TICKS = 200;
    private static final int HOLDING_REGION_HEADER_BYTES = 4096;
    private static final int HOLDING_REGION_SIDE_LENGTH = 32;
    private static final int HOLDING_REGION_LOG_SIDE_LENGTH = 5;
    private static final double BLOCKS_PER_SECTION_RENDER_DISTANCE = 512.0;
    private static final double DEDICATED_SERVER_HOLDING_CHUNK_WAKE_PADDING_BLOCKS = 1024.0;
    private static final Pattern HOLDING_REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.slvlr$");

    private static final ConfigSnapshot DISABLED_CONFIG = new ConfigSnapshot(false, 0.0, Long.MIN_VALUE);

    private static boolean sableUnavailable;
    private static ConfigSnapshot cachedConfig = DISABLED_CONFIG;
    private static long nextConfigRefreshTick;
    private static final Map<String, HoldingChunkIndex> holdingChunkIndexCache = new HashMap<>();
    private static final Map<ServerLevel, LongSet> activeChunkLoads = new WeakHashMap<>();

    private SableLodChunkManager() {
    }

    public static void updateTickets(ServerLevel level, LongSet trackedChunks, LongSet trackedHoldingChunks) {
        if (sableUnavailable) {
            clearTickets(level, trackedChunks, trackedHoldingChunks);
            return;
        }

        ConfigSnapshot config = getConfig(level.getGameTime());
        if (!config.enabled()) {
            clearTickets(level, trackedChunks, trackedHoldingChunks);
            return;
        }

        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                clearTickets(level, trackedChunks, trackedHoldingChunks);
                return;
            }

            if (level.players().isEmpty()) {
                clearTickets(level, trackedChunks, trackedHoldingChunks);
                return;
            }

            SubLevelHoldingChunkMap holdingChunkMap = container.getHoldingChunkMap();
            LongSet desiredChunks = new LongOpenHashSet();
            LongSet desiredHoldingChunks = new LongOpenHashSet();
            double maxHorizontalDistanceSquared = config.horizontalRenderDistanceBlocks() * config.horizontalRenderDistanceBlocks();
            double holdingChunkWakeDistance = config.horizontalRenderDistanceBlocks() + getHoldingChunkWakePadding(level, config);
            double holdingChunkWakeDistanceSquared = holdingChunkWakeDistance * holdingChunkWakeDistance;

            for (ServerSubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel.isRemoved()) {
                    continue;
                }

                BoundingBox3dc bounds = subLevel.boundingBox();
                if (bounds == null || !isWithinHorizontalDistance(level, bounds, maxHorizontalDistanceSquared)) {
                    continue;
                }

                addChunkBounds(bounds, desiredChunks);
            }

            if (holdingChunkMap != null) {
                updateHoldingChunkLoads(level, holdingChunkMap, desiredChunks, desiredHoldingChunks, trackedHoldingChunks, maxHorizontalDistanceSquared, holdingChunkWakeDistanceSquared);
            } else {
                trackedHoldingChunks.clear();
            }

            removeStaleTickets(level, trackedChunks, desiredChunks);
            addMissingTickets(level, trackedChunks, desiredChunks);
            activeChunkLoads.put(level, new LongOpenHashSet(desiredChunks));
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
            clearTickets(level, trackedChunks, trackedHoldingChunks);
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Voxy Sable LOD compatibility after direct access failed", e);
            sableUnavailable = true;
            clearTickets(level, trackedChunks, trackedHoldingChunks);
        }
    }

    public static void clearTickets(ServerLevel level, LongSet trackedChunks, LongSet trackedHoldingChunks) {
        activeChunkLoads.remove(level);

        if (!trackedChunks.isEmpty()) {
            LongIterator iterator = trackedChunks.iterator();
            while (iterator.hasNext()) {
                long chunk = iterator.nextLong();
                level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, new ChunkPos(chunk), TICKET_DISTANCE, new ChunkPos(chunk));
                iterator.remove();
            }
        }

        if (trackedHoldingChunks.isEmpty()) {
            return;
        }

        if (sableUnavailable) {
            trackedHoldingChunks.clear();
            return;
        }

        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            SubLevelHoldingChunkMap holdingChunkMap = container != null ? container.getHoldingChunkMap() : null;

            if (holdingChunkMap == null) {
                trackedHoldingChunks.clear();
                return;
            }

            LongIterator iterator = trackedHoldingChunks.iterator();
            while (iterator.hasNext()) {
                long chunk = iterator.nextLong();
                holdingChunkMap.updateChunkStatus(new ChunkPos(chunk), false);
                iterator.remove();
            }
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
            trackedHoldingChunks.clear();
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Failed clearing Voxy Sable holding chunk loads", e);
            trackedHoldingChunks.clear();
        }
    }

    public static boolean isWithinTrackingRange(double playerX, double playerZ, double targetX, double targetZ, long gameTime) {
        ConfigSnapshot config = getConfig(gameTime);
        if (!config.enabled()) {
            return false;
        }

        double dx = playerX - targetX;
        double dz = playerZ - targetZ;
        double maxDistance = config.horizontalRenderDistanceBlocks();
        return (dx * dx) + (dz * dz) <= maxDistance * maxDistance;
    }

    public static boolean shouldTreatChunkAsLoaded(ServerLevel level, int chunkX, int chunkZ, long gameTime) {
        ConfigSnapshot config = getConfig(gameTime);
        if (!config.enabled()) {
            return false;
        }

        if (isChunkWithinHorizontalDistance(level, new ChunkPos(chunkX, chunkZ),
                config.horizontalRenderDistanceBlocks() * config.horizontalRenderDistanceBlocks())) {
            return true;
        }

        LongSet activeChunks = activeChunkLoads.get(level);
        return activeChunks != null && activeChunks.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    private static void addMissingTickets(ServerLevel level, LongSet trackedChunks, LongSet desiredChunks) {
        LongIterator iterator = desiredChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (trackedChunks.add(chunk)) {
                level.getChunkSource().addRegionTicket(VOXY_SABLE_LOD_TICKET, new ChunkPos(chunk), TICKET_DISTANCE, new ChunkPos(chunk));
            }
        }
    }

    private static void removeStaleTickets(ServerLevel level, LongSet trackedChunks, LongSet desiredChunks) {
        LongIterator iterator = trackedChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (!desiredChunks.contains(chunk)) {
                level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, new ChunkPos(chunk), TICKET_DISTANCE, new ChunkPos(chunk));
                iterator.remove();
            }
        }
    }

    private static void updateHoldingChunkLoads(ServerLevel level,
                                                SubLevelHoldingChunkMap holdingChunkMap,
                                                LongSet desiredChunks,
                                                LongSet desiredHoldingChunks,
                                                LongSet trackedHoldingChunks,
                                                double maxHorizontalDistanceSquared,
                                                double holdingChunkWakeDistanceSquared) {
        LongSet knownHoldingChunks = new LongOpenHashSet();
        knownHoldingChunks.addAll(getHoldingChunkIndex(holdingChunkMap, level.getGameTime()).holdingChunks());
        addLoadedHoldingChunkKeys(holdingChunkMap, knownHoldingChunks);

        LongIterator iterator = knownHoldingChunks.iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunkKey);
            if (!isChunkWithinHorizontalDistance(level, chunkPos, holdingChunkWakeDistanceSquared)) {
                continue;
            }

            desiredHoldingChunks.add(chunkKey);

            if (trackedHoldingChunks.add(chunkKey)) {
                holdingChunkMap.updateChunkStatus(chunkPos, true);
            }

            SubLevelHoldingChunk holdingChunk = getOrLoadHoldingChunk(holdingChunkMap, chunkPos);
            if (holdingChunk == null) {
                continue;
            }

            for (HoldingSubLevel holdingSubLevel : holdingChunk.getLoadedHoldingSubLevels()) {
                BoundingBox3dc bounds = holdingSubLevel.data().bounds();
                if (bounds != null && isWithinHorizontalDistance(level, bounds, maxHorizontalDistanceSquared)) {
                    addChunkBounds(bounds, desiredChunks);
                }
            }
        }

        removeStaleHoldingChunkLoads(level, holdingChunkMap, trackedHoldingChunks, desiredHoldingChunks);
    }

    private static void removeStaleHoldingChunkLoads(ServerLevel level,
                                                     SubLevelHoldingChunkMap holdingChunkMap,
                                                     LongSet trackedHoldingChunks,
                                                     LongSet desiredHoldingChunks) {
        LongIterator iterator = trackedHoldingChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (desiredHoldingChunks.contains(chunk)) {
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(chunk);
            if (!PhysicsChunkTicketManager.isChunkLoadedEnough(level, chunkPos.x, chunkPos.z)) {
                holdingChunkMap.updateChunkStatus(chunkPos, false);
            }
            iterator.remove();
        }
    }

    private static void addChunkBounds(BoundingBox3dc bounds, LongSet desiredChunks) {
        int minChunkX = Mth.floor(bounds.minX()) >> 4;
        int maxChunkX = Mth.floor(bounds.maxX()) >> 4;
        int minChunkZ = Mth.floor(bounds.minZ()) >> 4;
        int maxChunkZ = Mth.floor(bounds.maxZ()) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                desiredChunks.add(ChunkPos.asLong(chunkX, chunkZ));
            }
        }
    }

    private static boolean isWithinHorizontalDistance(ServerLevel level, BoundingBox3dc bounds, double maxHorizontalDistanceSquared) {
        double minX = bounds.minX();
        double maxX = bounds.maxX();
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ();

        for (var player : level.players()) {
            double dx = distanceToRange(player.getX(), minX, maxX);
            double dz = distanceToRange(player.getZ(), minZ, maxZ);
            if ((dx * dx) + (dz * dz) <= maxHorizontalDistanceSquared) {
                return true;
            }
        }

        return false;
    }

    private static boolean isChunkWithinHorizontalDistance(ServerLevel level, ChunkPos chunkPos, double maxHorizontalDistanceSquared) {
        double minX = chunkPos.getMinBlockX();
        double maxX = chunkPos.getMaxBlockX() + 1.0;
        double minZ = chunkPos.getMinBlockZ();
        double maxZ = chunkPos.getMaxBlockZ() + 1.0;

        for (var player : level.players()) {
            double dx = distanceToRange(player.getX(), minX, maxX);
            double dz = distanceToRange(player.getZ(), minZ, maxZ);
            if ((dx * dx) + (dz * dz) <= maxHorizontalDistanceSquared) {
                return true;
            }
        }

        return false;
    }

    private static double getHoldingChunkWakePadding(ServerLevel level, ConfigSnapshot config) {
        if (level.getServer().isDedicatedServer()) {
            return DEDICATED_SERVER_HOLDING_CHUNK_WAKE_PADDING_BLOCKS;
        }
        return config.horizontalRenderDistanceBlocks() * 0.5;
    }

    private static double distanceToRange(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }

    private static ConfigSnapshot getConfig(long gameTime) {
        if (gameTime < nextConfigRefreshTick) {
            return cachedConfig;
        }
        nextConfigRefreshTick = gameTime + CONFIG_REFRESH_TICKS;

        long lastModified;
        try {
            lastModified = Files.exists(CONFIG_PATH) ? Files.getLastModifiedTime(CONFIG_PATH).toMillis() : Long.MIN_VALUE;
        } catch (IOException e) {
            Logger.error("Failed to stat Voxy config for Sable LOD compatibility", e);
            return cachedConfig;
        }

        if (cachedConfig.lastModifiedMillis() == lastModified) {
            return cachedConfig;
        }

        cachedConfig = loadConfig(lastModified);
        return cachedConfig;
    }

    private static ConfigSnapshot loadConfig(long lastModified) {
        if (!Files.exists(CONFIG_PATH)) {
            return new ConfigSnapshot(true, 16.0 * BLOCKS_PER_SECTION_RENDER_DISTANCE, lastModified);
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            boolean enabled = getBoolean(root, "enabled", true) && getBoolean(root, "enable_rendering", true);
            double sectionRenderDistance = getDouble(root, "section_render_distance", 16.0);

            if (!enabled || sectionRenderDistance <= 0.0) {
                return new ConfigSnapshot(false, 0.0, lastModified);
            }

            return new ConfigSnapshot(true, sectionRenderDistance * BLOCKS_PER_SECTION_RENDER_DISTANCE, lastModified);
        } catch (Exception e) {
            Logger.error("Failed to load Voxy config for Sable LOD compatibility", e);
            return new ConfigSnapshot(false, 0.0, lastModified);
        }
    }

    private static boolean getBoolean(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsBoolean();
    }

    private static double getDouble(JsonObject root, String key, double fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsDouble();
    }

    private static HoldingChunkIndex getHoldingChunkIndex(SubLevelHoldingChunkMap holdingChunkMap, long gameTime) {
        Path folder = getHoldingStorageFolder(holdingChunkMap);
        String key = folder.toAbsolutePath().normalize().toString();
        HoldingChunkIndex existing = holdingChunkIndexCache.get(key);
        if (existing != null && gameTime < existing.nextRefreshTick()) {
            return existing;
        }

        HoldingChunkIndex refreshed = new HoldingChunkIndex(scanHoldingChunks(folder), gameTime + HOLDING_INDEX_REFRESH_TICKS);
        holdingChunkIndexCache.put(key, refreshed);
        return refreshed;
    }

    private static LongSet scanHoldingChunks(Path folder) {
        LongSet holdingChunks = new LongOpenHashSet();
        if (!Files.isDirectory(folder)) {
            return holdingChunks;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.slvlr")) {
            for (Path path : stream) {
                addHoldingChunksFromRegion(path, holdingChunks);
            }
        } catch (IOException e) {
            Logger.error("Failed scanning Sable holding chunk index", e);
        }

        return holdingChunks;
    }

    private static void addHoldingChunksFromRegion(Path path, LongSet holdingChunks) {
        Matcher matcher = HOLDING_REGION_FILE_PATTERN.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return;
        }

        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        ByteBuffer header = ByteBuffer.allocate(HOLDING_REGION_HEADER_BYTES);

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (header.hasRemaining() && channel.read(header) > 0) {
                // Keep reading until the header is full or the file ends.
            }
        } catch (IOException e) {
            Logger.error("Failed reading Sable holding chunk region header " + path, e);
            return;
        }

        header.flip();
        IntBuffer spans = header.asIntBuffer();
        for (int index = 0; index < spans.remaining(); index++) {
            if (spans.get(index) == 0) {
                continue;
            }

            int localX = index & (HOLDING_REGION_SIDE_LENGTH - 1);
            int localZ = index >> HOLDING_REGION_LOG_SIDE_LENGTH;
            int chunkX = (regionX * HOLDING_REGION_SIDE_LENGTH) + localX;
            int chunkZ = (regionZ * HOLDING_REGION_SIDE_LENGTH) + localZ;
            holdingChunks.add(ChunkPos.asLong(chunkX, chunkZ));
        }
    }

    private static void addLoadedHoldingChunkKeys(SubLevelHoldingChunkMap holdingChunkMap, LongSet knownHoldingChunks) {
        Long2ObjectMap<?> loadedHoldingChunks = ((SableSubLevelHoldingChunkMapAccessor) holdingChunkMap).voxy$getLoadedHoldingChunks();
        knownHoldingChunks.addAll(loadedHoldingChunks.keySet());
    }

    private static SubLevelHoldingChunk getOrLoadHoldingChunk(SubLevelHoldingChunkMap holdingChunkMap, ChunkPos chunkPos) {
        return ((SableSubLevelHoldingChunkMapAccessor) holdingChunkMap).voxy$invokeGetOrLoadHoldingChunk(chunkPos, false);
    }

    private static Path getHoldingStorageFolder(SubLevelHoldingChunkMap holdingChunkMap) {
        SubLevelStorage storage = holdingChunkMap.getStorage();
        return ((SableSubLevelStorageAccessor) storage).voxy$getFolder();
    }

    private record ConfigSnapshot(boolean enabled, double horizontalRenderDistanceBlocks, long lastModifiedMillis) {
    }

    private record HoldingChunkIndex(LongSet holdingChunks, long nextRefreshTick) {
    }
}
