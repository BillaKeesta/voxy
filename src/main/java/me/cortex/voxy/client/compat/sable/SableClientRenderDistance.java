package me.cortex.voxy.client.compat.sable;

import me.cortex.voxy.client.config.VoxyConfig;

public final class SableClientRenderDistance {
    private static final int CHUNKS_PER_SECTION_RENDER_DISTANCE = 32;
    private static final int BLOCKS_PER_CHUNK = 16;

    private SableClientRenderDistance() {
    }

    public static int extendVanillaRenderDistanceChunks(int vanillaRenderDistanceChunks) {
        return Math.max(vanillaRenderDistanceChunks, getSimulatedContraptionRenderDistanceChunks());
    }

    public static double getRenderDistanceBlocks(int vanillaRenderDistanceChunks) {
        return extendVanillaRenderDistanceChunks(vanillaRenderDistanceChunks) * (double) BLOCKS_PER_CHUNK;
    }

    private static int getSimulatedContraptionRenderDistanceChunks() {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            return 0;
        }

        int percent = Math.max(0, Math.min(100, VoxyConfig.CONFIG.simulatedContraptionRenderDistancePercent));
        if (percent == 0 || VoxyConfig.CONFIG.sectionRenderDistance <= 0.0F) {
            return 0;
        }

        int voxyRenderDistanceChunks = (int) Math.ceil(VoxyConfig.CONFIG.sectionRenderDistance * CHUNKS_PER_SECTION_RENDER_DISTANCE);
        return (int) Math.ceil(voxyRenderDistanceChunks * (percent / 100.0D));
    }
}
