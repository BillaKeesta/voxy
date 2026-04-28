package me.cortex.voxy.commonImpl.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.common.Logger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;

public final class SableLodChunkManager {
    private static final TicketType<ChunkPos> VOXY_SABLE_LOD_TICKET = TicketType.create("voxy_sable_lod", Comparator.comparingLong(ChunkPos::toLong));
    private static final int TICKET_DISTANCE = 2;

    private static boolean sableUnavailable;

    private SableLodChunkManager() {
    }

    public static void updateTickets(ServerLevel level, LongSet trackedChunks) {
        if (level.getServer().isDedicatedServer()) {
            clearTickets(level, trackedChunks);
            return;
        }

        if (sableUnavailable) {
            clearTickets(level, trackedChunks);
            return;
        }

        double horizontalRenderDistanceBlocks = SableContraptionRenderDistance.getRangeBlocks(level);
        if (horizontalRenderDistanceBlocks <= 0.0) {
            clearTickets(level, trackedChunks);
            return;
        }

        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                clearTickets(level, trackedChunks);
                return;
            }

            if (container.getAllSubLevels().isEmpty() || level.players().isEmpty()) {
                clearTickets(level, trackedChunks);
                return;
            }

            LongSet desiredChunks = new LongOpenHashSet();
            double maxHorizontalDistanceSquared = horizontalRenderDistanceBlocks * horizontalRenderDistanceBlocks;

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

            removeStaleTickets(level, trackedChunks, desiredChunks);
            addMissingTickets(level, trackedChunks, desiredChunks);
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
            clearTickets(level, trackedChunks);
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Voxy Sable LOD compatibility after direct access failed", e);
            sableUnavailable = true;
            clearTickets(level, trackedChunks);
        }
    }

    public static void clearTickets(ServerLevel level, LongSet trackedChunks) {
        if (trackedChunks.isEmpty()) {
            return;
        }

        LongIterator iterator = trackedChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunk);
            level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, TICKET_DISTANCE, chunkPos);
            iterator.remove();
        }
    }

    private static void addMissingTickets(ServerLevel level, LongSet trackedChunks, LongSet desiredChunks) {
        LongIterator iterator = desiredChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (trackedChunks.add(chunk)) {
                ChunkPos chunkPos = new ChunkPos(chunk);
                level.getChunkSource().addRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, TICKET_DISTANCE, chunkPos);
            }
        }
    }

    private static void removeStaleTickets(ServerLevel level, LongSet trackedChunks, LongSet desiredChunks) {
        LongIterator iterator = trackedChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (!desiredChunks.contains(chunk)) {
                ChunkPos chunkPos = new ChunkPos(chunk);
                level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, TICKET_DISTANCE, chunkPos);
                iterator.remove();
            }
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

    private static double distanceToRange(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }
}
