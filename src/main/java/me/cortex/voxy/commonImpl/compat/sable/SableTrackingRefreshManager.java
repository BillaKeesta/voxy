package me.cortex.voxy.commonImpl.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.mixin.sable.SableSubLevelTrackingSystemAccessor;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class SableTrackingRefreshManager {
    private static final long REFRESH_INTERVAL_TICKS = 40L;
    private static final double REFRESH_BAND_BLOCKS = 64.0;
    private static final double POSITION_MATCH_PADDING_BLOCKS = 64.0;
    private static final int PARENT_CHUNK_PADDING = 1;

    private static final Map<TrackingKey, RefreshState> refreshStates = new HashMap<>();
    private static final Map<ServerLevel, List<SyntheticTrackingEntry>> syntheticTrackingEntries = new WeakHashMap<>();
    private static boolean sableUnavailable;
    private static boolean computingBaseShouldLoad;

    private SableTrackingRefreshManager() {
    }

    public static boolean isComputingBaseShouldLoad() {
        return computingBaseShouldLoad;
    }

    public static boolean shouldKeepExtendedTracking(ServerLevel level, Player player, Vector3dc entityPosition, long gameTime) {
        List<SyntheticTrackingEntry> entries = syntheticTrackingEntries.get(level);
        if (entries == null || entries.isEmpty()) {
            return false;
        }

        for (SyntheticTrackingEntry entry : entries) {
            if (!entry.matchesPosition(entityPosition)) {
                continue;
            }

            if (SableLodChunkManager.isWithinTrackingRange(
                    player.getX(),
                    player.getZ(),
                    clamp(player.getX(), entry.minX(), entry.maxX()),
                    clamp(player.getZ(), entry.minZ(), entry.maxZ()),
                    gameTime)) {
                return true;
            }
        }

        return false;
    }

    public static void tick(ServerLevel level, Object trackingSystem) {
        if (sableUnavailable) {
            refreshStates.clear();
            syntheticTrackingEntries.clear();
            return;
        }

        try {
            if (!(trackingSystem instanceof SableSubLevelTrackingSystemAccessor trackingAccessor)) {
                refreshStates.clear();
                syntheticTrackingEntries.clear();
                return;
            }

            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                refreshStates.clear();
                syntheticTrackingEntries.remove(level);
                return;
            }

            long gameTime = level.getGameTime();
            Set<TrackingKey> activeSyntheticKeys = new HashSet<>();
            List<SyntheticTrackingEntry> updatedSyntheticEntries = new ArrayList<>();

            for (ServerSubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel.isRemoved()) {
                    continue;
                }

                UUID subLevelId = subLevel.getUniqueId();
                if (subLevelId == null) {
                    continue;
                }

                Vector3dc position = subLevel.logicalPose().position();
                BoundingBox3dc bounds = subLevel.boundingBox();
                Collection<UUID> trackingPlayers = subLevel.getTrackingPlayers();
                boolean anyPlayerWithinExtendedRange = false;

                for (var player : level.players()) {
                    UUID playerId = player.getGameProfile().getId();
                    boolean withinExtendedRange = isWithinExtendedRange(player, bounds, position, gameTime);
                    boolean withinBaseRange = isWithinBaseTrackingRange(trackingAccessor, player, position);
                    anyPlayerWithinExtendedRange |= withinExtendedRange;

                    if (!withinExtendedRange || trackingPlayers.contains(playerId)) {
                        continue;
                    }

                    trackingPlayers.add(playerId);
                    trackingAccessor.voxy$invokeSendFullSync(player, subLevel, null);
                    if (!withinBaseRange) {
                        sendParentWorldChunks(level, player, bounds, position);
                    }
                }

                if (bounds != null && anyPlayerWithinExtendedRange) {
                    updatedSyntheticEntries.add(SyntheticTrackingEntry.from(position, bounds));
                }

                if (trackingPlayers.isEmpty()) {
                    continue;
                }

                for (UUID playerId : new ArrayList<>(trackingPlayers)) {
                    var trackedPlayer = level.getPlayerByUUID(playerId);
                    if (!(trackedPlayer instanceof ServerPlayer player)) {
                        continue;
                    }

                    double dx = player.getX() - position.x();
                    double dz = player.getZ() - position.z();
                    boolean withinBaseRange = isWithinBaseTrackingRange(trackingAccessor, player, position);
                    boolean withinExtendedRange = isWithinExtendedRange(player, bounds, position, gameTime);

                    TrackingKey key = new TrackingKey(playerId, subLevelId);
                    if (!withinExtendedRange || withinBaseRange) {
                        refreshStates.remove(key);
                        continue;
                    }

                    activeSyntheticKeys.add(key);

                    double horizontalDistanceSquared = (dx * dx) + (dz * dz);
                    int band = Mth.floor(Math.sqrt(horizontalDistanceSquared) / REFRESH_BAND_BLOCKS);
                    RefreshState state = refreshStates.get(key);
                    boolean shouldRefresh = state == null
                            || band < state.band()
                            || gameTime >= state.nextRefreshTick();

                    if (shouldRefresh) {
                        resendTrackedChunks(level, player, subLevel, bounds, position);
                        refreshStates.put(key, new RefreshState(gameTime + REFRESH_INTERVAL_TICKS, band));
                    } else if (band != state.band()) {
                        refreshStates.put(key, new RefreshState(state.nextRefreshTick(), band));
                    }
                }
            }

            refreshStates.keySet().removeIf(key -> !activeSyntheticKeys.contains(key));
            if (updatedSyntheticEntries.isEmpty()) {
                syntheticTrackingEntries.remove(level);
            } else {
                syntheticTrackingEntries.put(level, updatedSyntheticEntries);
            }
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
            refreshStates.clear();
            syntheticTrackingEntries.clear();
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Voxy Sable tracking refresh after direct access failed", e);
            sableUnavailable = true;
            refreshStates.clear();
            syntheticTrackingEntries.clear();
        }
    }

    private static boolean isWithinBaseTrackingRange(SableSubLevelTrackingSystemAccessor trackingAccessor, Player player, Vector3dc position) {
        computingBaseShouldLoad = true;
        try {
            return trackingAccessor.voxy$invokeShouldLoad(player, position);
        } finally {
            computingBaseShouldLoad = false;
        }
    }

    private static void resendTrackedChunks(ServerLevel level, ServerPlayer player, ServerSubLevel subLevel, BoundingBox3dc bounds, Vector3dc position) {
        LevelPlot plot = subLevel.getPlot();
        LevelLightEngine lightEngine = plot.getLightEngine();
        for (PlotChunkHolder chunkHolder : plot.getLoadedChunks()) {
            LevelChunk chunk = chunkHolder.getChunk();
            if (chunk == null) {
                continue;
            }
            player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null));
        }

        sendParentWorldChunks(level, player, bounds, position);
    }

    private static void sendParentWorldChunks(ServerLevel level, ServerPlayer player, BoundingBox3dc bounds, Vector3dc position) {
        ChunkTrackingView trackingView = player.getChunkTrackingView();
        if (trackingView == null) {
            trackingView = ChunkTrackingView.EMPTY;
        }

        int minChunkX;
        int maxChunkX;
        int minChunkZ;
        int maxChunkZ;
        if (bounds != null) {
            minChunkX = (Mth.floor(bounds.minX()) >> 4) - PARENT_CHUNK_PADDING;
            maxChunkX = (Mth.floor(bounds.maxX()) >> 4) + PARENT_CHUNK_PADDING;
            minChunkZ = (Mth.floor(bounds.minZ()) >> 4) - PARENT_CHUNK_PADDING;
            maxChunkZ = (Mth.floor(bounds.maxZ()) >> 4) + PARENT_CHUNK_PADDING;
        } else {
            int chunkX = Mth.floor(position.x()) >> 4;
            int chunkZ = Mth.floor(position.z()) >> 4;
            minChunkX = chunkX - PARENT_CHUNK_PADDING;
            maxChunkX = chunkX + PARENT_CHUNK_PADDING;
            minChunkZ = chunkZ - PARENT_CHUNK_PADDING;
            maxChunkZ = chunkZ + PARENT_CHUNK_PADDING;
        }

        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (trackingView.contains(chunkX, chunkZ)) {
                    continue;
                }

                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null));
            }
        }
    }

    private static boolean isWithinExtendedRange(ServerPlayer player, BoundingBox3dc bounds, Vector3dc position, long gameTime) {
        if (bounds == null) {
            return SableLodChunkManager.isWithinTrackingRange(
                    player.getX(),
                    player.getZ(),
                    position.x(),
                    position.z(),
                    gameTime);
        }

        return SableLodChunkManager.isWithinTrackingRange(
                player.getX(),
                player.getZ(),
                clamp(player.getX(), bounds.minX(), bounds.maxX()),
                clamp(player.getZ(), bounds.minZ(), bounds.maxZ()),
                gameTime);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private record TrackingKey(UUID playerId, UUID subLevelId) {
    }

    private record RefreshState(long nextRefreshTick, int band) {
    }

    private record SyntheticTrackingEntry(double minX, double maxX, double minZ, double maxZ) {
        private static SyntheticTrackingEntry from(Vector3dc position, BoundingBox3dc bounds) {
            double minX = Math.min(bounds.minX(), position.x());
            double maxX = Math.max(bounds.maxX(), position.x());
            double minZ = Math.min(bounds.minZ(), position.z());
            double maxZ = Math.max(bounds.maxZ(), position.z());
            return new SyntheticTrackingEntry(minX, maxX, minZ, maxZ);
        }

        private boolean matchesPosition(Vector3dc position) {
            return position.x() >= this.minX - POSITION_MATCH_PADDING_BLOCKS
                    && position.x() <= this.maxX + POSITION_MATCH_PADDING_BLOCKS
                    && position.z() >= this.minZ - POSITION_MATCH_PADDING_BLOCKS
                    && position.z() <= this.maxZ + POSITION_MATCH_PADDING_BLOCKS;
        }
    }
}
