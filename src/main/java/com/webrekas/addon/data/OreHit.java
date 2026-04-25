package com.webrekas.addon.data;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * One ore cluster returned by the WASM. Holds the centroid + size metadata,
 * plus a cache slot for real block positions discovered by the tick-based
 * expansion scan (populated once the cluster's chunk is loaded client-side).
 */
public final class OreHit {

    private final OreType type;
    private final BlockPos pos;
    private final int ores;
    private final String size;
    private final String confidence;

    /**
     * Real block positions inside this cluster, discovered by scanning the loaded
     * chunk around {@link #pos}. {@code null} means "scan not attempted yet";
     * empty list means "scanned, nothing found" (cluster mined out or chunk was
     * only partially loaded when we peeked).
     */
    private volatile List<BlockPos> expandedBlocks = null;

    /** True once the scan has run at least once against a loaded chunk. */
    private volatile boolean scanAttempted = false;

    public OreHit(OreType type, BlockPos pos, int ores, String size, String confidence) {
        this.type = type;
        this.pos = pos;
        this.ores = ores;
        this.size = size;
        this.confidence = confidence;
    }

    public OreType type()      { return type; }
    public BlockPos pos()      { return pos; }
    public int ores()          { return ores; }
    public String size()       { return size; }
    public String confidence() { return confidence; }

    public List<BlockPos> expandedBlocks() { return expandedBlocks; }
    public boolean scanAttempted()          { return scanAttempted; }

    public void setExpandedBlocks(List<BlockPos> blocks) {
        this.expandedBlocks = blocks;
        this.scanAttempted = true;
    }

    public boolean isLowConfidence() {
        return "LOW".equalsIgnoreCase(confidence);
    }

    @Override
    public String toString() {
        return "OreHit{" + type + " at " + pos + ", ores=" + ores
            + ", size=" + size + ", conf=" + confidence + "}";
    }
}
