package me.cortex.voxy.commonImpl.compat.sable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SableContraptionRenderDistance {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("voxy-config.json");
    private static final int CONFIG_REFRESH_TICKS = 20;
    private static final int CHUNKS_PER_SECTION_RENDER_DISTANCE = 32;
    private static final int BLOCKS_PER_CHUNK = 16;
    private static final double DEFAULT_SECTION_RENDER_DISTANCE = 16.0;
    private static final int DEFAULT_PERCENT = 50;
    private static final double DEDICATED_SERVER_FALLBACK_BLOCKS = 2048.0;

    private static final ConfigSnapshot DISABLED_CONFIG = new ConfigSnapshot(false, 0.0, DEFAULT_PERCENT, Long.MIN_VALUE);

    private static ConfigSnapshot cachedConfig = DISABLED_CONFIG;
    private static long nextConfigRefreshTick;

    private SableContraptionRenderDistance() {
    }

    public static double getRangeBlocks(ServerLevel level) {
        if (level.getServer().isDedicatedServer()) {
            return DEDICATED_SERVER_FALLBACK_BLOCKS;
        }

        ConfigSnapshot config = getConfig(level.getGameTime());
        if (!config.enabled()) {
            return 0.0;
        }

        int renderDistanceChunks = (int) Math.ceil(config.sectionRenderDistance() * CHUNKS_PER_SECTION_RENDER_DISTANCE);
        int percent = Math.max(0, Math.min(100, config.simulatedContraptionRenderDistancePercent()));
        int contraptionDistanceChunks = (int) Math.ceil(renderDistanceChunks * (percent / 100.0D));
        return contraptionDistanceChunks * BLOCKS_PER_CHUNK;
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
            Logger.error("Failed to stat Voxy config for Sable simulated contraption render distance", e);
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
            return new ConfigSnapshot(true, DEFAULT_SECTION_RENDER_DISTANCE, DEFAULT_PERCENT, lastModified);
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            boolean enabled = getBoolean(root, "enabled", true) && getBoolean(root, "enable_rendering", true);
            double sectionRenderDistance = getDouble(root, "section_render_distance", DEFAULT_SECTION_RENDER_DISTANCE);
            int simulatedContraptionPercent = getInt(root, "simulated_contraption_render_distance_percent", DEFAULT_PERCENT);

            if (!enabled || sectionRenderDistance <= 0.0) {
                return new ConfigSnapshot(false, 0.0, simulatedContraptionPercent, lastModified);
            }

            return new ConfigSnapshot(true, sectionRenderDistance, simulatedContraptionPercent, lastModified);
        } catch (Exception e) {
            Logger.error("Failed to load Voxy config for Sable simulated contraption render distance", e);
            return DISABLED_CONFIG;
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

    private static int getInt(JsonObject root, String key, int fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsInt();
    }

    private record ConfigSnapshot(
            boolean enabled,
            double sectionRenderDistance,
            int simulatedContraptionRenderDistancePercent,
            long lastModifiedMillis
    ) {
    }
}
