package com.webrekas.addon.modules;

import com.webrekas.addon.OrekasAddon;
import com.webrekas.addon.data.OreHit;
import com.webrekas.addon.data.OrePlatform;
import com.webrekas.addon.data.OreType;
import com.webrekas.addon.wasm.OreFinderWasm;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class OreFinderModule extends Module {

    // Phase 0 — explicit search state replaces the (inflight bool + volatile pos) pair
    private enum SearchState { IDLE, SEARCHING }

    private static final ExecutorService WASM_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orekas-wasm-0");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static volatile OreFinderWasm sharedWasm;

    // ---- settings ----------------------------------------------------------

    private final SettingGroup sgInput    = this.settings.getDefaultGroup();
    private final SettingGroup sgOres     = this.settings.createGroup("Ores");
    private final SettingGroup sgRender   = this.settings.createGroup("Render");
    private final SettingGroup sgColors   = this.settings.createGroup("Colors");
    private final SettingGroup sgAdvanced = this.settings.createGroup("Advanced");

    // Input group
    private final Setting<String> worldSeed = sgInput.add(new StringSetting.Builder()
        .name("world-seed")
        .description("World seed (required on multiplayer; on single-player it's auto-detected).")
        .defaultValue("")
        .build());

    private final Setting<OrePlatform> platform = sgInput.add(new EnumSetting.Builder<OrePlatform>()
        .name("platform")
        .description("Minecraft edition + version for the ore distribution model.")
        .defaultValue(OrePlatform.JAVA_1_21)
        .build());

    private final Setting<Boolean> refreshNow = sgInput.add(new BoolSetting.Builder()
        .name("refresh-now")
        .description("Toggle ON to trigger an immediate search.")
        .defaultValue(false)
        .onChanged(v -> { if (v) { triggerSearch(true); resetRefreshButton(); } })
        .build());

    // Ores group — Diamond ON by default
    private final Setting<Boolean> oreDiamond       = oreToggle("diamond", true);
    private final Setting<Boolean> oreAncientDebris = oreToggle("ancient-debris", false);
    private final Setting<Boolean> oreGold          = oreToggle("gold", false);
    private final Setting<Boolean> oreIron          = oreToggle("iron", false);
    private final Setting<Boolean> oreCopper        = oreToggle("copper", false);
    private final Setting<Boolean> oreEmerald       = oreToggle("emerald", false);
    private final Setting<Boolean> oreRedstone      = oreToggle("redstone", false);
    private final Setting<Boolean> oreLapis         = oreToggle("lapis", false);
    private final Setting<Boolean> oreCoal          = oreToggle("coal", false);

    private Setting<Boolean> oreToggle(String name, boolean def) {
        return sgOres.add(new BoolSetting.Builder()
            .name(name).description("Include this ore in the search + render.")
            .defaultValue(def).build());
    }

    // Render group
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How boxes are drawn.")
        .defaultValue(ShapeMode.Both).build());

    private final Setting<Integer> fillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha")
        .description("Alpha (0..255) for the box fill. Outline uses the ore's color at full alpha.")
        .defaultValue(35).range(0, 255).sliderRange(0, 255).build());

    private final Setting<Boolean> nametagForUnloaded = sgRender.add(new BoolSetting.Builder()
        .name("nametag-for-unloaded")
        .description("For clusters whose chunks aren't loaded client-side, show a floating \"Nx Ore\" label.")
        .defaultValue(true).build());

    private final Setting<Double> nametagScale = sgRender.add(new DoubleSetting.Builder()
        .name("nametag-scale")
        .description("Size of the floating labels.")
        .defaultValue(1.0).range(0.3, 3.0).sliderRange(0.3, 3.0).build());

    // Colors group — one SettingColor per ore type
    private final Map<OreType, Setting<SettingColor>> colorSettings = new EnumMap<>(OreType.class);
    {
        for (OreType t : OreType.values()) {
            colorSettings.put(t, sgColors.add(new ColorSetting.Builder()
                .name(t.name().toLowerCase().replace('_', '-') + "-color")
                .description("Render color for " + t.label() + ".")
                .defaultValue(t.defaultColor()).build()));
        }
    }

    // Advanced group — replaces the old static tuning constants (Phase 3)
    // Defaults are identical to the old constants, so existing user configs are unaffected.
    private final Setting<Integer> chunkRadius = sgAdvanced.add(new IntSetting.Builder()
        .name("chunk-radius")
        .description("Search radius in chunks (6 → 13×13 chunk area).")
        .defaultValue(6).min(1).max(16).sliderRange(1, 12).build());

    private final Setting<Integer> veinScanRadius = sgAdvanced.add(new IntSetting.Builder()
        .name("vein-scan-radius")
        .description("Block radius around each centroid scanned for real ore blocks.")
        .defaultValue(8).min(1).max(16).sliderRange(1, 16).build());

    private final Setting<Integer> expansionsPerTick = sgAdvanced.add(new IntSetting.Builder()
        .name("expansions-per-tick")
        .description("Max cluster scans per tick during the tick-budget expansion phase.")
        .defaultValue(128).min(1).max(512).sliderRange(1, 256).build());

    private final Setting<Integer> fetchCooldownMs = sgAdvanced.add(new IntSetting.Builder()
        .name("fetch-cooldown-ms")
        .description("Minimum milliseconds between WASM searches.")
        .defaultValue(1000).min(100).max(30000).sliderRange(200, 10000).build());

    private final Setting<Boolean> showLowConfidence = sgAdvanced.add(new BoolSetting.Builder()
        .name("show-low-confidence")
        .description("Render LOW-confidence WASM predictions.")
        .defaultValue(false).build());

    private final Setting<Integer> largeVeinThreshold = sgAdvanced.add(new IntSetting.Builder()
        .name("large-vein-threshold")
        .description("Skip centroid if >N same-type blocks found along X or Z axis in a 50-block window. Suppresses false positives in iron/coal bands.")
        .defaultValue(50).min(10).max(200).sliderRange(10, 100).build());

    // ---- state -------------------------------------------------------------

    // Phase 1b — atomic snapshot: the render thread always reads a complete consistent map.
    private final AtomicReference<Map<OreType, List<OreHit>>> cache =
        new AtomicReference<>(emptyCache());

    // Phase 2a — explicit state machine: replaces (AtomicBoolean inflight + volatile BlockPos)
    private final AtomicReference<SearchState> searchState =
        new AtomicReference<>(SearchState.IDLE);
    private final AtomicLong lastFetchMillis = new AtomicLong(0);
    private final AtomicReference<BlockPos> lastFetchPos = new AtomicReference<>(null);

    // Phase 2c — jitter rolled once per search completion, not per tick.
    // ±20% of 16²=256: range [204, 307]. Prevents predictable re-fetch timing.
    private final AtomicLong jitteredRefreshThreshold = new AtomicLong(256L);

    private static Map<OreType, List<OreHit>> emptyCache() {
        Map<OreType, List<OreHit>> m = new EnumMap<>(OreType.class);
        for (OreType t : OreType.values()) m.put(t, List.of());
        return Collections.unmodifiableMap(m);
    }

    public OreFinderModule() {
        super(OrekasAddon.CATEGORY, "ore-finder",
            "Per-block ore ESP using orefinder.gg's WASM in-process. Validates against live world state.");
    }

    @Override
    public String getInfoString() {
        int total = cache.get().values().stream().mapToInt(List::size).sum();
        return searchState.get().name().toLowerCase() + " | " + total + " clusters";
    }

    @Override
    public void onActivate() {
        if (!runsHere(mc.world, mc.player)) return;
        Long seed = resolveSeed();
        if (seed == null) {
            error("Could not resolve world seed. On MP, set world-seed manually.");
            return;
        }
        triggerSearch(true);
    }

    /**
     * Whether the module should be active in the current dimension + Y context.
     * End: never. Overworld Y≥0: skipped (anti-xray heuristic only valid below Y=0).
     * Overworld below Y=0 / Nether: active.
     */
    private boolean runsHere(ClientWorld world, PlayerEntity player) {
        if (world == null || player == null) return false;
        Dimension dim = PlayerUtils.getDimension();
        if (dim == Dimension.End) return false;
        if (dim == Dimension.Overworld && player.getBlockY() >= 0) return false;
        return true;
    }

    @Override
    public void onDeactivate() {
        cache.set(emptyCache());
    }

    private void resetRefreshButton() {
        Setting<?> s = settings.get("refresh-now");
        if (s instanceof BoolSetting b) b.set(false);
    }

    // ---- search triggering -------------------------------------------------

    private void triggerSearch(boolean userInitiated) {
        // Phase 2d — guard: executor cannot accept tasks if it was shut down externally.
        if (WASM_EXECUTOR.isShutdown()) {
            if (userInitiated) error("WASM executor is shut down — restart Minecraft.");
            return;
        }

        PlayerEntity player = mc.player;
        if (player == null) { if (userInitiated) warning("Not in a world."); return; }
        if (!runsHere(mc.world, player)) {
            if (userInitiated) warning("Not active here. Runs in Nether, or in Overworld below Y=0.");
            return;
        }

        List<OreType> enabled = enabledOres();
        if (enabled.isEmpty()) {
            if (userInitiated) warning("Enable at least one ore under the Ores group.");
            return;
        }

        Long seed = resolveSeed();
        if (seed == null) {
            if (userInitiated) error("Could not resolve world seed. Set world-seed manually on MP.");
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = (long) fetchCooldownMs.get();
        long since = now - lastFetchMillis.get();
        if (since < cooldown) {
            if (userInitiated) warning("Cooldown active. %d s remaining.", (cooldown - since) / 1000 + 1);
            return;
        }

        // Phase 2b — CAS on SearchState replaces the two-field implicit state.
        if (!searchState.compareAndSet(SearchState.IDLE, SearchState.SEARCHING)) {
            if (userInitiated) info("A search is already running.");
            return;
        }

        BlockPos origin = player.getBlockPos();
        lastFetchPos.set(origin);
        lastFetchMillis.set(now);

        OrePlatform plat = platform.get();
        int radius = chunkRadius.get();  // capture before crossing thread boundary

        WASM_EXECUTOR.submit(() -> {
            try {
                OreFinderWasm wasm = sharedWasm();
                // Phase 1c — build full snapshot in WASM thread; never touch the live cache.
                Map<OreType, List<OreHit>> next = new EnumMap<>(OreType.class);
                for (OreType t : OreType.values()) next.put(t, List.of());
                int total = 0;
                for (OreType t : enabled) {
                    try {
                        List<OreHit> hits = wasm.findOres(seed, plat, t,
                            origin.getX(), origin.getZ(), radius);
                        next.put(t, Collections.unmodifiableList(new ArrayList<>(hits)));
                        total += hits.size();
                    } catch (Throwable perOreEx) {
                        OrekasAddon.LOG.error("[Orekas] search failed for " + t, perOreEx);
                        error("search failed for %s: %s", t.label(), perOreEx.getClass().getSimpleName());
                    }
                }
                // Single atomic swap — render thread always sees a complete consistent map.
                cache.set(Collections.unmodifiableMap(next));
                info("cached %d clusters across %d ore type(s).", total, enabled.size());
            } catch (Throwable ex) {
                OrekasAddon.LOG.error("[Orekas] WASM search failed", ex);
                error("search failed: %s: %s", ex.getClass().getSimpleName(), String.valueOf(ex.getMessage()));
            } finally {
                searchState.set(SearchState.IDLE);
                // Phase 2c — re-roll jitter once per search, not per tick.
                jitteredRefreshThreshold.set(
                    (long)(256.0 * (0.8 + ThreadLocalRandom.current().nextDouble(0.4))));
            }
        });
    }

    private List<OreType> enabledOres() {
        // Ancient Debris only generates in the Nether.
        boolean overworld = PlayerUtils.getDimension() == Dimension.Overworld;
        List<OreType> out = new ArrayList<>();
        if (oreDiamond.get())                     out.add(OreType.DIAMOND);
        if (oreAncientDebris.get() && !overworld) out.add(OreType.ANCIENT_DEBRIS);
        if (oreGold.get())                        out.add(OreType.GOLD);
        if (oreIron.get())                        out.add(OreType.IRON);
        if (oreCopper.get())                      out.add(OreType.COPPER);
        if (oreEmerald.get())                     out.add(OreType.EMERALD);
        if (oreRedstone.get())                    out.add(OreType.REDSTONE);
        if (oreLapis.get())                       out.add(OreType.LAPIS);
        if (oreCoal.get())                        out.add(OreType.COAL);
        return out;
    }

    private Long resolveSeed() {
        MinecraftServer server = mc.getServer();
        if (server != null) {
            ServerWorld overworld = server.getOverworld();
            if (overworld != null) return overworld.getSeed();
        }
        String raw = worldSeed.get();
        if (raw == null || raw.isBlank()) return null;
        try { return Long.parseLong(raw.trim()); }
        catch (NumberFormatException e) {
            try { return Long.parseUnsignedLong(raw.trim()); }
            catch (NumberFormatException ignored) { return null; }
        }
    }

    private static OreFinderWasm sharedWasm() {
        OreFinderWasm s = sharedWasm;
        if (s == null) {
            synchronized (OreFinderModule.class) {
                s = sharedWasm;
                if (s == null) s = sharedWasm = OreFinderWasm.loadBundled();
            }
        }
        return s;
    }

    // ---- chunk-load expansion ---------------------------------------------

    /**
     * When a chunk lands, immediately expand any pending centroids whose vein scan
     * window overlaps the newly loaded chunk. Defers until all neighbour chunks are
     * loaded to prevent partial scans being permanently locked by the CAS.
     */
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        ClientWorld world = mc.world;
        if (!runsHere(world, mc.player)) return;
        int cx = event.chunk().getPos().x;
        int cz = event.chunk().getPos().z;

        for (OreType t : OreType.values()) {
            Set<Block> targets = t.targetBlocks();
            // Phase 1d — read from the atomic snapshot.
            for (OreHit h : cache.get().get(t)) {
                if (h.scanAttempted()) continue;
                BlockPos c = h.pos();
                int hcx = c.getX() >> 4;
                int hcz = c.getZ() >> 4;
                if (Math.abs(hcx - cx) > 1 || Math.abs(hcz - cz) > 1) continue;
                if (!allNeighborsLoaded(world, c)) continue;
                if (!showLowConfidence.get() && h.isLowConfidence()) {
                    h.setExpandedBlocks(List.of());
                    continue;
                }
                if (isLargeVeinFalsePositive(world, c, targets)) {
                    h.setExpandedBlocks(List.of());
                    continue;
                }
                h.setExpandedBlocks(scanCluster(world, c, targets));
            }
        }
    }

    // Phase 3d — allNeighborsLoaded now uses the configurable veinScanRadius setting.
    private boolean allNeighborsLoaded(ClientWorld world, BlockPos c) {
        int r = veinScanRadius.get();
        int x0 = (c.getX() - r) >> 4, x1 = (c.getX() + r) >> 4;
        int z0 = (c.getZ() - r) >> 4, z1 = (c.getZ() + r) >> 4;
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                if (!world.isChunkLoaded(x, z)) return false;
        return true;
    }

    // ---- tick-based expansion ---------------------------------------------

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientWorld world = mc.world;
        if (world == null) return;
        if (!runsHere(world, mc.player)) return;

        // Phase 2c — single atomic read; jitter stored in AtomicLong, not re-rolled here.
        BlockPos prevPos = lastFetchPos.get();
        PlayerEntity player = mc.player;
        if (player != null && prevPos != null) {
            BlockPos p = player.getBlockPos();
            long dx = p.getX() - prevPos.getX();
            long dz = p.getZ() - prevPos.getZ();
            if (dx * dx + dz * dz > jitteredRefreshThreshold.get()) triggerSearch(false);
        }

        // Phase 4c — tick-budget expansion sweep.
        // Skip the entire loop when every cluster has already been scanned — the common
        // steady-state case where the player is not moving and all chunks are loaded.
        Map<OreType, List<OreHit>> snapshot = cache.get();
        boolean anyPending = false;
        outer:
        for (OreType t : OreType.values()) {
            for (OreHit h : snapshot.get(t)) {
                if (!h.scanAttempted()) { anyPending = true; break outer; }
            }
        }

        if (anyPending) {
            int budget = expansionsPerTick.get();
            boolean showLow = showLowConfidence.get();
            for (OreType t : OreType.values()) {
                if (budget <= 0) break;
                Set<Block> targets = t.targetBlocks();
                for (OreHit h : snapshot.get(t)) {
                    if (budget <= 0) break;
                    if (h.scanAttempted()) continue;
                    if (!showLow && h.isLowConfidence()) {
                        h.setExpandedBlocks(List.of());
                        continue;
                    }
                    BlockPos c = h.pos();
                    if (!allNeighborsLoaded(world, c)) continue;
                    if (isLargeVeinFalsePositive(world, c, targets)) {
                        h.setExpandedBlocks(List.of());
                        continue;
                    }
                    h.setExpandedBlocks(scanCluster(world, c, targets));
                    budget--;
                }
            }
        }
    }

    /**
     * Section-based cluster scan.
     *
     * Iterates ChunkSection[] directly rather than calling world.getBlockState() per block.
     * The inner X/Z bounds are pre-computed per chunk so the hot loop contains no conditional
     * branches — it only iterates columns that are within the scan radius.
     * Cost: O(sections_in_window × lx_cols × lz_cols × y_rows), all integer arithmetic
     * with palette array reads in the innermost position.
     */
    private List<BlockPos> scanCluster(ClientWorld world, BlockPos center, Set<Block> targets) {
        int radius = veinScanRadius.get();
        List<BlockPos> found = new ArrayList<>();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        int chunkX0 = (cx - radius) >> 4, chunkX1 = (cx + radius) >> 4;
        int chunkZ0 = (cz - radius) >> 4, chunkZ1 = (cz + radius) >> 4;
        int worldXMin = cx - radius, worldXMax = cx + radius;
        int worldZMin = cz - radius, worldZMax = cz + radius;

        for (int chx = chunkX0; chx <= chunkX1; chx++) {
            for (int chz = chunkZ0; chz <= chunkZ1; chz++) {
                if (!world.isChunkLoaded(chx, chz)) continue;
                WorldChunk chunk = world.getChunk(chx, chz);
                ChunkSection[] sections = chunk.getSectionArray();
                int baseY = chunk.getBottomY();
                int worldXBase = chx << 4, worldZBase = chz << 4;

                // Pre-compute column bounds for this chunk to eliminate per-block conditionals
                int lxMin = Math.max(0,  worldXMin - worldXBase);
                int lxMax = Math.min(15, worldXMax - worldXBase);
                int lzMin = Math.max(0,  worldZMin - worldZBase);
                int lzMax = Math.min(15, worldZMax - worldZBase);

                for (int si = 0; si < sections.length; si++) {
                    ChunkSection section = sections[si];
                    if (section == null || section.isEmpty()) continue;
                    int sectionBaseY = baseY + si * 16;

                    // Clamp Y scan to where the search window overlaps this section
                    int yMin = Math.max(0,  cy - radius - sectionBaseY);
                    int yMax = Math.min(15, cy + radius - sectionBaseY);
                    if (yMax < 0 || yMin > 15) continue;

                    for (int lx = lxMin; lx <= lxMax; lx++) {
                        int worldX = worldXBase + lx;
                        for (int lz = lzMin; lz <= lzMax; lz++) {
                            int worldZ = worldZBase + lz;
                            for (int ly = yMin; ly <= yMax; ly++) {
                                if (targets.contains(section.getBlockState(lx, ly, lz).getBlock()))
                                    found.add(new BlockPos(worldX, sectionBaseY + ly, worldZ));
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    // Phase 4b — large-vein false-positive filter.
    // Scans ±25 blocks along X and Z axes. If >threshold same-type blocks are found along
    // either axis, the centroid is likely sitting in a natural band (iron/coal layer) rather
    // than a localized vein, and should be suppressed.
    //
    // Y-axis intentionally omitted: deepslate bands extend horizontally, not vertically.
    // In anti-xray environments the buried ores are replaced with stone, so the Y scan
    // would see only stone and would never fire — 51 wasted getBlockState() calls per tick.
    private boolean isLargeVeinFalsePositive(ClientWorld world, BlockPos center, Set<Block> targets) {
        final int HALF = 25;
        int threshold = largeVeinThreshold.get();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        int worldMinY = world.getBottomY(), worldMaxY = world.getBottomY() + world.getHeight() - 1;
        BlockPos.Mutable probe = new BlockPos.Mutable();

        // Clamp Y to world bounds to avoid probing outside the loaded world height.
        int clampedY = Math.max(worldMinY, Math.min(worldMaxY, cy));

        int xCount = 0;
        for (int dx = -HALF; dx <= HALF; dx++) {
            probe.set(cx + dx, clampedY, cz);
            if (!world.isChunkLoaded(probe.getX() >> 4, probe.getZ() >> 4)) continue;
            if (targets.contains(world.getBlockState(probe).getBlock()) && ++xCount > threshold)
                return true;
        }
        int zCount = 0;
        for (int dz = -HALF; dz <= HALF; dz++) {
            probe.set(cx, clampedY, cz + dz);
            if (!world.isChunkLoaded(probe.getX() >> 4, probe.getZ() >> 4)) continue;
            if (targets.contains(world.getBlockState(probe).getBlock()) && ++zCount > threshold)
                return true;
        }
        return false;
    }

    /**
     * Should the WASM-predicted centroid be drawn as a "trust the prediction" box?
     * Mirrors Paper anti-xray engine-mode-2: ores are hidden only when all six face-neighbours
     * are opaque. If the centroid block is fully buried in opaque blocks, the server is hiding
     * a real ore there and we trust the WASM prediction.
     */
    private static boolean shouldShowCentroidPrediction(
            ClientWorld world, BlockPos c, Set<Block> targets) {
        BlockState self = world.getBlockState(c);
        if (targets.contains(self.getBlock())) return false;
        if (!self.isOpaque()) return false;
        for (Direction dir : Direction.values()) {
            BlockPos n = c.offset(dir);
            if (!world.isChunkLoaded(n.getX() >> 4, n.getZ() >> 4)) continue;
            if (!world.getBlockState(n).isOpaque()) return false;
        }
        return true;
    }

    // ---- render ------------------------------------------------------------

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        ClientWorld world = mc.world;
        if (world == null) return;
        if (!runsHere(world, mc.player)) return;

        int alpha = fillAlpha.get();
        ShapeMode mode = shapeMode.get();
        boolean showLow = showLowConfidence.get();

        for (OreType t : OreType.values()) {
            // Phase 1d — read from the atomic snapshot.
            List<OreHit> hits = cache.get().get(t);
            if (hits.isEmpty()) continue;
            SettingColor line = colorSettings.get(t).get();
            Color side = new Color(line.r, line.g, line.b, alpha);
            Set<Block> targets = t.targetBlocks();

            for (OreHit h : hits) {
                if (!showLow && h.isLowConfidence()) continue;
                BlockPos centroid = h.pos();
                if (!world.isChunkLoaded(centroid.getX() >> 4, centroid.getZ() >> 4)) continue;

                List<BlockPos> expanded = h.expandedBlocks();
                if (expanded != null && !expanded.isEmpty()) {
                    for (BlockPos p : expanded) {
                        if (!targets.contains(world.getBlockState(p).getBlock())) continue;
                        event.renderer.box(
                            p.getX(), p.getY(), p.getZ(),
                            p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0,
                            side, line, mode, 0);
                    }
                }

                if (h.scanAttempted()
                    && expanded != null && expanded.isEmpty()
                    && shouldShowCentroidPrediction(world, centroid, targets)) {
                    event.renderer.box(
                        centroid.getX(), centroid.getY(), centroid.getZ(),
                        centroid.getX() + 1.0, centroid.getY() + 1.0, centroid.getZ() + 1.0,
                        side, line, mode, 0);
                }
            }
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!nametagForUnloaded.get()) return;
        ClientWorld world = mc.world;
        if (world == null) return;
        if (!runsHere(world, mc.player)) return;

        TextRenderer text = TextRenderer.get();
        double scale = nametagScale.get();

        for (OreType t : OreType.values()) {
            // Phase 1d — read from the atomic snapshot.
            List<OreHit> hits = cache.get().get(t);
            if (hits.isEmpty()) continue;
            SettingColor lineColor = colorSettings.get(t).get();
            Color tagColor = new Color(lineColor.r, lineColor.g, lineColor.b, 255);

            for (OreHit h : hits) {
                if (h.scanAttempted()) continue;
                if (!showLowConfidence.get() && h.isLowConfidence()) continue;
                BlockPos c = h.pos();
                if (world.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) continue;

                Vector3d pos = new Vector3d(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
                if (!NametagUtils.to2D(pos, scale)) continue;

                String label = h.ores() + "x " + t.label();
                NametagUtils.begin(pos);
                text.beginBig();
                double w = text.getWidth(label);
                double hgt = text.getHeight();
                text.render(label, -w / 2.0, -hgt, tagColor, true);
                text.end();
                NametagUtils.end();
            }
        }
    }
}
