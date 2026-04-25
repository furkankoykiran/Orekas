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
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class OreFinderModule extends Module {

    // ---- static tuning (was in settings, now fixed per user request) -------

    private static final int CHUNK_RADIUS = 6;              // 13x13 chunks = 208x208 blocks
    private static final int VEIN_SCAN_RADIUS = 8;          // max MC vein size in 1.21
    private static final int EXPANSIONS_PER_TICK = 48;
    private static final long FETCH_COOLDOWN_MS = 1_000;
    private static final long AUTO_REFRESH_DISTANCE_SQ = 16L * 16L;
    private static final boolean SHOW_LOW_CONFIDENCE = false;
    private static final boolean AUTO_DETECT_SEED = true;

    private static final ExecutorService WASM_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "orekas-wasm-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });
    private static volatile OreFinderWasm sharedWasm;

    // ---- settings ----------------------------------------------------------

    private final SettingGroup sgInput    = this.settings.getDefaultGroup();
    private final SettingGroup sgOres     = this.settings.createGroup("Ores");
    private final SettingGroup sgRender   = this.settings.createGroup("Render");
    private final SettingGroup sgColors   = this.settings.createGroup("Colors");

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

    // Ores — in site order, Diamond ON by default
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

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How boxes are drawn.")
        .defaultValue(ShapeMode.Both).build());

    private final Setting<Integer> fillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha")
        .description("Alpha (0..255) for the box fill. Outline uses the ore's color at full alpha.")
        .defaultValue(35).range(0, 255).sliderRange(0, 255).build());

    private final Setting<Boolean> nametagForUnloaded = sgRender.add(new BoolSetting.Builder()
        .name("nametag-for-unloaded")
        .description("For clusters whose chunks aren't loaded client-side, show a floating \"Nx Ore\" label instead of a fake box.")
        .defaultValue(true).build());

    private final Setting<Double> nametagScale = sgRender.add(new DoubleSetting.Builder()
        .name("nametag-scale")
        .description("Size of the floating labels.")
        .defaultValue(1.0).range(0.3, 3.0).sliderRange(0.3, 3.0).build());

    private final Map<OreType, Setting<SettingColor>> colorSettings = new EnumMap<>(OreType.class);
    {
        for (OreType t : OreType.values()) {
            colorSettings.put(t, sgColors.add(new ColorSetting.Builder()
                .name(t.name().toLowerCase().replace('_', '-') + "-color")
                .description("Render color for " + t.label() + ".")
                .defaultValue(t.defaultColor()).build()));
        }
    }

    // ---- state -------------------------------------------------------------

    private final Map<OreType, List<OreHit>> cache = new EnumMap<>(OreType.class);
    private final AtomicBoolean inflight = new AtomicBoolean(false);
    private final AtomicLong lastFetchMillis = new AtomicLong(0);
    private volatile BlockPos lastFetchPos = null;

    public OreFinderModule() {
        super(OrekasAddon.CATEGORY, "ore-finder", "Per-block ore ESP using orefinder.gg's WASM in-process. Validates against live world state.");
        for (OreType t : OreType.values()) cache.put(t, new CopyOnWriteArrayList<>());
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
     * <ul>
     *   <li>End: never runs.</li>
     *   <li>Overworld + Y &gt;= 0: skipped — the anti-xray heuristic is only valid
     *       below Y=0 on AntiXray-style servers (deepslate region masking).</li>
     *   <li>Overworld below Y=0 / Nether: runs.</li>
     * </ul>
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
        for (List<OreHit> l : cache.values()) l.clear();
    }

    private void resetRefreshButton() {
        Setting<?> s = settings.get("refresh-now");
        if (s instanceof BoolSetting b) b.set(false);
    }

    // ---- search triggering -------------------------------------------------

    private void triggerSearch(boolean userInitiated) {
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
        long since = now - lastFetchMillis.get();
        if (since < FETCH_COOLDOWN_MS) {
            if (userInitiated) warning("Cooldown active. %d s remaining.", (FETCH_COOLDOWN_MS - since) / 1000 + 1);
            return;
        }
        if (!inflight.compareAndSet(false, true)) {
            if (userInitiated) info("A search is already running.");
            return;
        }
        BlockPos origin = player.getBlockPos();
        lastFetchPos = origin;
        lastFetchMillis.set(now);

        OrePlatform plat = platform.get();

        WASM_EXECUTOR.submit(() -> {
            try {
                OreFinderWasm wasm = sharedWasm();
                int total = 0;
                for (OreType t : enabled) {
                    try {
                        List<OreHit> hits = wasm.findOres(seed, plat, t,
                            origin.getX(), origin.getZ(), CHUNK_RADIUS);
                        List<OreHit> cached = cache.get(t);
                        cached.clear();
                        cached.addAll(hits);
                        total += hits.size();
                    } catch (Throwable perOreEx) {
                        OrekasAddon.LOG.error("[Orekas] search failed for " + t, perOreEx);
                        error("search failed for %s: %s", t.label(), perOreEx.getClass().getSimpleName());
                    }
                }
                for (OreType t : OreType.values()) {
                    if (!enabled.contains(t)) cache.get(t).clear();
                }
                info("cached %d clusters across %d ore type(s).", total, enabled.size());
            } catch (Throwable ex) {
                OrekasAddon.LOG.error("[Orekas] WASM search failed", ex);
                error("search failed: %s: %s", ex.getClass().getSimpleName(), String.valueOf(ex.getMessage()));
            } finally {
                inflight.set(false);
            }
        });
    }

    private List<OreType> enabledOres() {
        // Ancient Debris only generates in the Nether — never search for it in the
        // Overworld even if the user toggle is on. Called from triggerSearch on the
        // tick thread, so mc.world access is safe.
        boolean overworld = PlayerUtils.getDimension() == Dimension.Overworld;
        List<OreType> out = new ArrayList<>();
        if (oreDiamond.get())                    out.add(OreType.DIAMOND);
        if (oreAncientDebris.get() && !overworld) out.add(OreType.ANCIENT_DEBRIS);
        if (oreGold.get())                       out.add(OreType.GOLD);
        if (oreIron.get())                       out.add(OreType.IRON);
        if (oreCopper.get())                     out.add(OreType.COPPER);
        if (oreEmerald.get())                    out.add(OreType.EMERALD);
        if (oreRedstone.get())                   out.add(OreType.REDSTONE);
        if (oreLapis.get())                      out.add(OreType.LAPIS);
        if (oreCoal.get())                       out.add(OreType.COAL);
        return out;
    }

    private Long resolveSeed() {
        if (AUTO_DETECT_SEED) {
            MinecraftServer server = mc.getServer();
            if (server != null) {
                ServerWorld overworld = server.getOverworld();
                if (overworld != null) return overworld.getSeed();
            }
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
     * When a chunk lands, immediately expand any pending centroids that fall
     * inside it (or its 8 neighbors, since the vein scan radius can spill across
     * a chunk boundary). This sidesteps the per-tick budget for the common
     * "I just walked into render distance" case — ESP appears as soon as the
     * data is available, not on the next expansion-budget cycle.
     */
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        ClientWorld world = mc.world;
        if (!runsHere(world, mc.player)) return;
        WorldChunk chunk = event.chunk();
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;

        for (OreType t : OreType.values()) {
            Set<Block> targets = t.targetBlocks();
            for (OreHit h : cache.get(t)) {
                if (h.scanAttempted()) continue;
                BlockPos c = h.pos();
                int hcx = c.getX() >> 4;
                int hcz = c.getZ() >> 4;
                // Only consider centroids whose 3x3 chunk window includes this chunk.
                if (Math.abs(hcx - cx) > 1 || Math.abs(hcz - cz) > 1) continue;
                // Defer until every chunk the scan needs is loaded; partial scans
                // would mark the cluster done with missing blocks.
                if (!allNeighborsLoaded(world, c)) continue;
                if (!SHOW_LOW_CONFIDENCE && h.isLowConfidence()) {
                    h.setExpandedBlocks(List.of());
                    continue;
                }
                h.setExpandedBlocks(scanCluster(world, c, targets));
            }
        }
    }

    private static boolean allNeighborsLoaded(ClientWorld world, BlockPos c) {
        int x0 = (c.getX() - VEIN_SCAN_RADIUS) >> 4, x1 = (c.getX() + VEIN_SCAN_RADIUS) >> 4;
        int z0 = (c.getZ() - VEIN_SCAN_RADIUS) >> 4, z1 = (c.getZ() + VEIN_SCAN_RADIUS) >> 4;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!world.isChunkLoaded(x, z)) return false;
            }
        }
        return true;
    }

    // ---- tick-based expansion ---------------------------------------------

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientWorld world = mc.world;
        if (world == null) return;
        if (!runsHere(world, mc.player)) return;

        // Auto-refresh on significant movement
        PlayerEntity player = mc.player;
        if (player != null && lastFetchPos != null) {
            BlockPos p = player.getBlockPos();
            long dx = p.getX() - lastFetchPos.getX();
            long dz = p.getZ() - lastFetchPos.getZ();
            if (dx * dx + dz * dz > AUTO_REFRESH_DISTANCE_SQ) triggerSearch(false);
        }

        // Expand up to EXPANSIONS_PER_TICK pending clusters whose chunks are loaded.
        int budget = EXPANSIONS_PER_TICK;
        for (OreType t : OreType.values()) {
            if (budget <= 0) break;
            Set<Block> targets = t.targetBlocks();
            for (OreHit h : cache.get(t)) {
                if (budget <= 0) break;
                if (h.scanAttempted()) continue;
                if (!SHOW_LOW_CONFIDENCE && h.isLowConfidence()) { h.setExpandedBlocks(List.of()); continue; }
                BlockPos c = h.pos();
                if (!world.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) continue;
                h.setExpandedBlocks(scanCluster(world, c, targets));
                budget--;
            }
        }
    }

    private static List<BlockPos> scanCluster(ClientWorld world, BlockPos center, Set<Block> targets) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos.Mutable probe = new BlockPos.Mutable();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        for (int dx = -VEIN_SCAN_RADIUS; dx <= VEIN_SCAN_RADIUS; dx++) {
            for (int dy = -VEIN_SCAN_RADIUS; dy <= VEIN_SCAN_RADIUS; dy++) {
                for (int dz = -VEIN_SCAN_RADIUS; dz <= VEIN_SCAN_RADIUS; dz++) {
                    int x = cx + dx, y = cy + dy, z = cz + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    probe.set(x, y, z);
                    if (targets.contains(world.getBlockState(probe).getBlock())) {
                        found.add(probe.toImmutable());
                    }
                }
            }
        }
        return found;
    }

    /**
     * Should the WASM-predicted centroid be drawn as a "trust the prediction" box?
     *
     * <p>The rule mirrors Paper's anti-xray engine-mode-2 logic: that engine only
     * replaces ores with stone when all six face-neighbors are opaque full cubes.
     * So if the centroid block isn't a target ore but is fully buried in opaque
     * blocks, the server is likely hiding a real ore there and we should trust
     * the WASM. If the centroid has any non-opaque neighbor (air, glass, water,
     * leaves, slab), the server would have shipped the real ore — the fact that
     * we see a non-ore block means it's mined or WASM was wrong.
     */
    private static boolean shouldShowCentroidPrediction(
            ClientWorld world, BlockPos c, Set<Block> targets) {
        BlockState self = world.getBlockState(c);
        // Centroid already rendered as a real block in the per-block ESP loop.
        if (targets.contains(self.getBlock())) return false;
        // Non-opaque at centroid (air / glass / water / leaves) ⇒ we see it's not ore.
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

        for (OreType t : OreType.values()) {
            List<OreHit> hits = cache.get(t);
            if (hits.isEmpty()) continue;
            SettingColor line = colorSettings.get(t).get();
            Color side = new Color(line.r, line.g, line.b, alpha);
            Set<Block> targets = t.targetBlocks();

            for (OreHit h : hits) {
                if (!SHOW_LOW_CONFIDENCE && h.isLowConfidence()) continue;
                BlockPos centroid = h.pos();

                // Chunk unloaded → skip 3D rendering; Render2DEvent handles the nametag.
                if (!world.isChunkLoaded(centroid.getX() >> 4, centroid.getZ() >> 4)) continue;

                // Per-block ESP for real target blocks we've scanned and validated.
                // Captures: vanilla worlds (full vein visible) + exposed portions of
                // clusters on anti-xray servers (server sends real block for exposed ores).
                List<BlockPos> expanded = h.expandedBlocks();
                if (expanded != null && !expanded.isEmpty()) {
                    for (BlockPos p : expanded) {
                        if (!targets.contains(world.getBlockState(p).getBlock())) continue; // mined → skip
                        event.renderer.box(
                            p.getX(), p.getY(), p.getZ(),
                            p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0,
                            side, line, mode, 0);
                    }
                }

                // Anti-xray bypass / WASM prediction overlay: only kick in when the scan
                // actually ran and found *no* real target blocks in the vein vicinity.
                // On vanilla worlds this skips the overlay entirely (the vein's real
                // blocks already render via block ESP), preventing the "extra box on a
                // stone block near the centroid" false positive. On anti-xray servers,
                // fully-buried clusters where the server sent stone everywhere still
                // render the predicted centroid.
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

        // We need begin/end scoping. Iterate eligible hits and render each nametag.
        for (OreType t : OreType.values()) {
            List<OreHit> hits = cache.get(t);
            if (hits.isEmpty()) continue;
            SettingColor lineColor = colorSettings.get(t).get();
            Color tagColor = new Color(lineColor.r, lineColor.g, lineColor.b, 255);

            for (OreHit h : hits) {
                if (h.scanAttempted()) continue; // chunk was loaded and scan ran; not eligible for nametag
                if (!SHOW_LOW_CONFIDENCE && h.isLowConfidence()) continue;
                BlockPos c = h.pos();
                if (world.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) continue; // chunk loaded, just waiting for next tick

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
