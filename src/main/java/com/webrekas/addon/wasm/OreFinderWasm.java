package com.webrekas.addon.wasm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.webrekas.addon.data.OreHit;
import com.webrekas.addon.data.OrePlatform;
import com.webrekas.addon.data.OreType;
import net.minecraft.util.math.BlockPos;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Owns a single Chicory {@link Instance} of orefinder.gg's Rust WASM module.
 *
 * <p>Not thread-safe — callers must serialize all {@link #findOres} calls,
 * typically via a single-threaded executor.
 */
public final class OreFinderWasm {

    private final Instance instance;
    private final ExternRefs refs;

    public static OreFinderWasm loadBundled() {
        try (InputStream is = OreFinderWasm.class.getResourceAsStream("/orekas/rust_bg.wasm")) {
            if (is == null) throw new IllegalStateException("orekas/rust_bg.wasm not on classpath");
            return load(is.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load bundled WASM", e);
        }
    }

    public static OreFinderWasm load(byte[] wasmBytes) {
        WasmModule module = Parser.parse(wasmBytes);
        ExternRefs refs = new ExternRefs();
        WasmImports imports = new WasmImports(refs);

        Store store = new Store();
        store.addFunction(imports.functions());
        Instance instance = store.instantiate("orefinder", module);

        imports.bind(instance);
        try {
            ExportFunction start = instance.export("__wbindgen_start");
            if (start != null) start.apply();
        } catch (Throwable ignored) {
            // Idempotent / may already have fired during instantiate()
        }
        return new OreFinderWasm(instance, refs);
    }

    private OreFinderWasm(Instance instance, ExternRefs refs) {
        this.instance = instance;
        this.refs = refs;
    }

    /**
     * Run the ore search and convert the raw WASM output into {@link OreHit}s.
     *
     * @param seed          the world seed (full 64-bit value)
     * @param platform      edition + version enum from orefinder.gg
     * @param ore           single ore type per call (one {@code OreFinder} instance per ore)
     * @param centerBlockX  world-space X of the player
     * @param centerBlockZ  world-space Z of the player
     * @param chunkRadius   search extent: a {@code (2r+1) x (2r+1)} chunk area centered on the player
     */
    public List<OreHit> findOres(long seed, OrePlatform platform, OreType ore,
                                 int centerBlockX, int centerBlockZ, int chunkRadius) {
        List<Map<String, Object>> raw = findOresRaw(
            (int) (seed & 0xFFFFFFFFL),
            (int) (seed >>> 32),
            platform.edition(), platform.version(),
            Double.NaN, 0,
            ore.wasmId(),
            (centerBlockX >> 4) - chunkRadius,
            (centerBlockZ >> 4) - chunkRadius,
            chunkRadius * 2 + 1,
            chunkRadius * 2 + 1);

        List<OreHit> out = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            int x = asInt(m.get("x"));
            int y = asInt(m.get("y"));
            int z = asInt(m.get("z"));
            int ores = asInt(m.get("ores"));
            String confidence = String.valueOf(m.getOrDefault("confidence", "DEFAULT"));
            String size = sizeFromKey(String.valueOf(m.getOrDefault("key", "")));
            out.add(new OreHit(ore, new BlockPos(x, y, z), ores, size, confidence));
        }
        return out;
    }

    /** Low-level variant: returns the raw List-of-Map the WASM produced (useful for tests + debugging). */
    public List<Map<String, Object>> findOresRaw(
            int seedLow, int seedHigh, int edition, int version,
            double biomeSize, int largeBiomes,
            int oreType, int chunkX, int chunkZ, int sizeX, int sizeZ) {

        long world = i32Ret(
            instance.export("world_new").apply(seedLow, seedHigh, edition, version,
                Double.doubleToRawLongBits(biomeSize), largeBiomes));
        long finder = i32Ret(instance.export("orefinder_new").apply(world, oreType));
        long zone = i32Ret(instance.export("zone_new").apply(chunkX, chunkZ, sizeX, sizeZ));

        long[] findResult = instance.export("orefinder_find").apply(finder, zone);
        long resultExternRef = findResult[0];

        Object raw = refs.get(resultExternRef);
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object el : list) {
                if (el instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    out.add(cast);
                }
            }
        }
        return out;
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /** Keys look like "medium/13917/11683/1" — first segment is the size tier. */
    private static String sizeFromKey(String key) {
        int slash = key.indexOf('/');
        return slash < 0 ? "" : key.substring(0, slash);
    }

    private static long i32Ret(long[] vals) {
        return vals[0] & 0xFFFF_FFFFL;
    }

    public Instance instance() { return instance; }
    public ExternRefs refs() { return refs; }
}
