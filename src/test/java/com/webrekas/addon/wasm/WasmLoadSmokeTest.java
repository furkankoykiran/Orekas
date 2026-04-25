package com.webrekas.addon.wasm;

import com.webrekas.addon.data.OreHit;
import com.webrekas.addon.data.OrePlatform;
import com.webrekas.addon.data.OreType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test: load the bundled WASM, run a fixed diamond search on
 * Java 1.21 seed {@code 6608149111735331168} around (222714, 186940) and verify
 * the expected near-origin cluster appears in the results.
 * <p>
 * Fixture captured live from https://www.orefinder.gg on 2026-04-24 — the first
 * cluster in the site's output was {@code (222715, -47, 186942)}.
 */
class WasmLoadSmokeTest {

    private static final long SEED = 6608149111735331168L;
    private static final int EDITION_JAVA = 1;
    private static final int VERSION_V1_21 = 102100;
    private static final int ORE_DIAMOND = 1;

    private static final int PLAYER_X = 222714;
    private static final int PLAYER_Z = 186940;
    private static final int CHUNK_RADIUS = 2;

    @Test
    void findsDiamondClustersMatchingOrefinderGg_rawApi() {
        OreFinderWasm wasm = OreFinderWasm.loadBundled();

        int chunkX = PLAYER_X >> 4;
        int chunkZ = PLAYER_Z >> 4;
        int size = CHUNK_RADIUS * 2 + 1;

        int seedLow = (int) (SEED & 0xFFFFFFFFL);
        int seedHigh = (int) (SEED >>> 32);

        List<Map<String, Object>> hits = wasm.findOresRaw(
            seedLow, seedHigh, EDITION_JAVA, VERSION_V1_21,
            Double.NaN, 0,
            ORE_DIAMOND, chunkX - CHUNK_RADIUS, chunkZ - CHUNK_RADIUS, size, size);

        System.out.println("[orekas] raw diamond hits: " + hits.size());
        assertNotNull(hits);
        assertFalse(hits.isEmpty(), "expected at least one diamond cluster in the search zone");

        boolean found = hits.stream().anyMatch(h ->
            "DEFAULT".equals(String.valueOf(h.get("confidence"))) &&
            intOf(h, "x") == 222715 && intOf(h, "y") == -47 && intOf(h, "z") == 186942);
        assertTrue(found, "expected the known fixture cluster (222715,-47,186942) to be present in the raw WASM output");
    }

    @Test
    void findsDiamondClustersMatchingOrefinderGg_highLevelApi() {
        // This is the path the in-game module uses. Lock its correctness with
        // the same fixture to catch regressions in the low-to-high-level layer.
        OreFinderWasm wasm = OreFinderWasm.loadBundled();

        List<OreHit> hits = wasm.findOres(
            SEED, OrePlatform.JAVA_1_21, OreType.DIAMOND,
            PLAYER_X, PLAYER_Z, CHUNK_RADIUS);

        System.out.println("[orekas] high-level OreHit count: " + hits.size());
        assertFalse(hits.isEmpty());
        boolean fixtureFound = hits.stream().anyMatch(h ->
            h.type() == OreType.DIAMOND
            && h.pos().getX() == 222715 && h.pos().getY() == -47 && h.pos().getZ() == 186942
            && !h.isLowConfidence());
        assertTrue(fixtureFound, "fixture (222715,-47,186942) should appear in high-level OreHit output with DEFAULT confidence");
    }

    private static int intOf(Map<String, Object> h, String k) {
        Object v = h.get(k);
        if (v instanceof Number n) return n.intValue();
        return Integer.MIN_VALUE;
    }
}
