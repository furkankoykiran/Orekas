package com.webrekas.addon.data;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class OreHit {

    private final OreType type;
    private final BlockPos pos;
    private final int ores;
    private final String size;
    private final String confidence;

    // null   = not scanned yet
    // List.of() = scanned, nothing found (buried / mined / false-positive)
    // non-empty  = real target blocks discovered by scanCluster
    private final AtomicReference<List<BlockPos>> expandedBlocks = new AtomicReference<>(null);

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

    public List<BlockPos> expandedBlocks() { return expandedBlocks.get(); }
    public boolean scanAttempted()          { return expandedBlocks.get() != null; }

    public void setExpandedBlocks(List<BlockPos> blocks) {
        List<BlockPos> result = List.copyOf(blocks);
        // First writer wins (null → result). Also allow upgrading an empty result to real
        // blocks: a partial-chunk scan produces List.of() but a later full scan may find ores.
        // An empty result is never overwritten with another empty result.
        if (!expandedBlocks.compareAndSet(null, result)) {
            if (!result.isEmpty()) expandedBlocks.set(result);
        }
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
