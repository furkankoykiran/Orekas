package com.webrekas.addon.modules;

import com.webrekas.addon.OrekasAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.*;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills and confirms DonutSMP player orders automatically.
 *
 * Key design decisions:
 *  - All timing is driven by a single "refresh-interval" tick setting — no hidden floors.
 *  - Order slots are validated (must have a price AND a player name) before clicking to
 *    prevent accidentally clicking on GUI decoration elements with the same item type.
 *  - Shulker contents are checked via DataComponentTypes.CONTAINER (not tooltip parsing)
 *    so the result is authoritative regardless of tooltip display mode.
 *  - A 1-second per-order cooldown prevents re-attempting the same order immediately
 *    after a failed cycle.
 *  - stage/stageStart are volatile so getInfoString() is race-free on the render thread.
 */
public class OrderSniperModule extends Module {

    // Watchdog timeouts — these are the MAXIMUM time to wait in each stage before giving up.
    // They are NOT delays; the module advances as soon as the expected state is observed.
    private static final long T_GUI_OPEN    = 3_000;   // /orders → GUI appears
    private static final long T_DEPOSIT     = 4_000;   // order click → deposit GUI appears
    private static final long T_CONFIRM     = 4_000;   // items sent → confirm GUI appears
    private static final long T_GLASS       = 3_000;   // confirm GUI open → green glass found

    // ── State machine ─────────────────────────────────────────────────────────

    private enum Stage {
        IDLE, OPEN_ORDERS, WAIT_GUI, SCAN_ORDERS,
        WAIT_DEPOSIT, DRAIN_INVENTORY, WAIT_CONFIRM, CONFIRM_SALE,
        FINALIZE, CYCLE_PAUSE
    }

    private volatile Stage stage      = Stage.IDLE;
    private volatile long  stageStart = 0L;

    private int    ordersGUISyncId  = -1;
    private int    depositGUISyncId = -1;
    private int    drainCursor      = 0;
    private String lastSeller       = null;   // seller of the currently-active order
    private double lastPrice        = 0;      // price of the currently-active order

    /** Fingerprint → epoch-ms of last attempt. Prevents immediate retry of same order. */
    private final Map<String, Long> recentOrders = new HashMap<>();

    // ── Settings ──────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgBlacklist = settings.createGroup("Blacklist");
    private final SettingGroup sgAdminList = settings.createGroup("Admin List");

    private final Setting<String> searchQuery = sgGeneral.add(new StringSetting.Builder()
        .name("search-query")
        .description("Argument for /orders <query>.")
        .defaultValue("diamond")
        .build());

    private final Setting<Item> deliverItem = sgGeneral.add(new ItemSetting.Builder()
        .name("deliver-item")
        .description("Item type to deliver to matched orders.")
        .defaultValue(Items.DIAMOND)
        .build());

    private final Setting<String> minPrice = sgGeneral.add(new StringSetting.Builder()
        .name("min-price")
        .description("Minimum order price (e.g. 100, 2.5k, 1m, 1b).")
        .defaultValue("1")
        .build());

    private final Setting<Boolean> shulkerMode = sgGeneral.add(new BoolSetting.Builder()
        .name("shulker-mode")
        .description("Deliver shulker boxes that contain the target item (empty shulkers are skipped).")
        .defaultValue(false)
        .build());

    private final Setting<Integer> refreshInterval = sgGeneral.add(new IntSetting.Builder()
        .name("refresh-interval")
        .description("Ticks to wait between cycles and after each GUI interaction. 0 = as fast as possible.")
        .defaultValue(3)
        .min(0).max(40).sliderMax(20)
        .build());

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show status messages in chat.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Log detailed state transitions and decisions to chat.")
        .defaultValue(false)
        .build());

    private final Setting<List<String>> blacklistedPlayers = sgBlacklist.add(new StringListSetting.Builder()
        .name("blacklisted-players")
        .description("Skip orders from these players.")
        .defaultValue(List.of())
        .build());

    private final Setting<AdminListModule.Role> adminListRole = sgAdminList.add(new EnumSetting.Builder<AdminListModule.Role>()
        .name("role")
        .description("Blacklist: skip admin orders. Whitelist: only accept admin orders.")
        .defaultValue(AdminListModule.Role.OFF)
        .build());

    // ── Constructor ───────────────────────────────────────────────────────────

    public OrderSniperModule() {
        super(OrekasAddon.CATEGORY, "order-sniper",
            "Automatically fills and confirms DonutSMP player orders.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        if (parsePrice(minPrice.get()) < 0) {
            error("Invalid min-price. Use a number with optional k/m/b suffix.");
            toggle();
            return;
        }
        reset();
        advance(Stage.OPEN_ORDERS);
    }

    @Override
    public void onDeactivate() {
        stage = Stage.IDLE;
        recentOrders.clear();
    }

    private void reset() {
        ordersGUISyncId = depositGUISyncId = -1;
        drainCursor = 0;
        lastSeller = null;
        lastPrice = 0;
    }

    // ── Tick handler ──────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();
        pruneRecentOrders(now);

        switch (stage) {
            case OPEN_ORDERS     -> tickOpenOrders(now);
            case WAIT_GUI        -> tickWaitGui(now);
            case SCAN_ORDERS     -> tickScanOrders(now);
            case WAIT_DEPOSIT    -> tickWaitDeposit(now);
            case DRAIN_INVENTORY -> tickDrain(now);
            case WAIT_CONFIRM    -> tickWaitConfirm(now);
            case CONFIRM_SALE    -> tickConfirm(now);
            case FINALIZE        -> tickFinalize(now);
            case CYCLE_PAUSE     -> { if (elapsed(now) >= interval()) advance(Stage.OPEN_ORDERS); }
            default              -> {}
        }
    }

    // ── Stage handlers ────────────────────────────────────────────────────────

    private void tickOpenOrders(long now) {
        // Guard at start of cycle: stop if nothing to deliver
        if (!hasDeliverableItems()) {
            if (notifications.get()) info("No more items to deliver. Stopping.");
            toggle();
            return;
        }
        dbg("→ /orders %s", searchQuery.get());
        ChatUtils.sendPlayerMsg("/orders " + searchQuery.get());
        advance(Stage.WAIT_GUI);
    }

    private void tickWaitGui(long now) {
        // No minimum floor — purely driven by refreshInterval
        if (elapsed(now) < interval()) return;
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            ordersGUISyncId = screen.getScreenHandler().syncId;
            dbg("GUI opened syncId=%d", ordersGUISyncId);
            advance(Stage.SCAN_ORDERS);
        } else if (elapsed(now) > T_GUI_OPEN) {
            dbg("GUI timeout, retrying");
            advance(Stage.OPEN_ORDERS);
        }
    }

    private void tickScanOrders(long now) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            if (elapsed(now) > 600) advance(Stage.OPEN_ORDERS);
            return;
        }
        ScreenHandler h = screen.getScreenHandler();
        double threshold = parsePrice(minPrice.get());

        for (Slot slot : h.slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !stack.isOf(deliverItem.get())) continue;

            double price = extractPrice(stack);
            if (price < threshold) continue;

            // Require a player name — decoration elements rarely have order metadata
            String seller = extractPlayerName(stack);
            if (seller == null) { dbg("slot %d has price but no seller, skipping", slot.id); continue; }
            if (isBlacklisted(seller)) { dbg("seller %s blacklisted", seller); continue; }
            if (isFilteredByAdminList(seller)) { dbg("seller %s filtered by AdminList", seller); continue; }

            // 1-second cooldown: don't re-click an order we just failed on
            String fingerprint = seller + ":" + (long) price;
            Long lastAttempt = recentOrders.get(fingerprint);
            if (lastAttempt != null && now - lastAttempt < 1_000) {
                dbg("order %s on cooldown (%dms)", fingerprint, now - lastAttempt);
                continue;
            }

            lastSeller = seller;
            lastPrice  = price;
            recentOrders.put(fingerprint, now);
            ordersGUISyncId = h.syncId;

            mc.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
            dbg("clicked order slot %d seller=%s price=%s", slot.id, seller, formatPrice(price));
            advance(Stage.WAIT_DEPOSIT);
            return;
        }

        if (elapsed(now) > T_GUI_OPEN) {
            dbg("no matching order, refreshing");
            advance(Stage.CYCLE_PAUSE);
        }
    }

    private void tickWaitDeposit(long now) {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            int id = screen.getScreenHandler().syncId;
            if (id != ordersGUISyncId) {
                depositGUISyncId = id;
                drainCursor = 0;
                dbg("deposit GUI opened syncId=%d", id);
                advance(Stage.DRAIN_INVENTORY);
                return;
            }
        }
        if (elapsed(now) > T_DEPOSIT) {
            dbg("deposit GUI timeout");
            if (mc.currentScreen != null) mc.player.closeHandledScreen();
            advance(Stage.OPEN_ORDERS);
        }
    }

    /**
     * Sends refreshInterval+1 QUICK_MOVE packets per tick.
     * At refreshInterval=0 we send 4/tick; at refreshInterval=1 we send 2/tick;
     * at refreshInterval=2+ we send 1/tick. Space is re-checked before each packet.
     */
    private void tickDrain(long now) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            dbg("drain: screen closed, advancing to confirm");
            advance(Stage.WAIT_CONFIRM);
            return;
        }
        ScreenHandler h = screen.getScreenHandler();
        PlayerInventory playerInv = mc.player.getInventory();

        int ri = refreshInterval.get();
        int batch = ri == 0 ? 4 : ri == 1 ? 2 : 1;

        for (int i = 0; i < batch; i++) {
            while (drainCursor < 36 && !isDeliverable(playerInv.getStack(drainCursor))) {
                drainCursor++;
            }
            if (drainCursor >= 36) {
                dbg("drain complete, closing screen");
                mc.player.closeHandledScreen();
                advance(Stage.WAIT_CONFIRM);
                return;
            }
            boolean chestHasSpace = h.slots.stream()
                .anyMatch(s -> s.inventory != playerInv && s.getStack().isEmpty());
            if (!chestHasSpace) {
                dbg("chest full, closing screen");
                mc.player.closeHandledScreen();
                advance(Stage.WAIT_CONFIRM);
                return;
            }
            int guiId = findGuiSlotId(h, playerInv, drainCursor);
            if (guiId >= 0) {
                mc.interactionManager.clickSlot(h.syncId, guiId, 0, SlotActionType.QUICK_MOVE, mc.player);
                dbg("drain slot %d → gui %d", drainCursor, guiId);
            }
            drainCursor++;
        }
    }

    private void tickWaitConfirm(long now) {
        if (elapsed(now) < interval()) return;
        if (mc.currentScreen instanceof GenericContainerScreen) {
            dbg("confirm GUI detected");
            advance(Stage.CONFIRM_SALE);
        } else if (elapsed(now) > T_CONFIRM) {
            dbg("confirm GUI timeout, cycling");
            advance(Stage.CYCLE_PAUSE);
        }
    }

    private void tickConfirm(long now) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            if (elapsed(now) > T_GLASS) { dbg("glass timeout"); advance(Stage.CYCLE_PAUSE); }
            return;
        }
        ScreenHandler h = screen.getScreenHandler();
        for (Slot slot : h.slots) {
            if (isConfirmGlass(slot.getStack())) {
                mc.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                dbg("confirmed sale slot %d", slot.id);
                advance(Stage.FINALIZE);
                return;
            }
        }
        if (elapsed(now) > T_GLASS) { dbg("no glass found, cycling"); advance(Stage.CYCLE_PAUSE); }
    }

    private void tickFinalize(long now) {
        if (elapsed(now) < interval()) return;
        if (mc.currentScreen != null) mc.player.closeHandledScreen();
        // Always log the completed sale — user said this should be unconditional
        if (notifications.get() && lastSeller != null) {
            info("Sold to (highlight)%s(default) for (highlight)%s", lastSeller, formatPrice(lastPrice));
        }
        advance(Stage.CYCLE_PAUSE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void advance(Stage next) {
        dbg("stage %s → %s", stage, next);
        stage      = next;
        stageStart = System.currentTimeMillis();
        if (next != Stage.DRAIN_INVENTORY) drainCursor = 0;
    }

    private long elapsed(long now) { return now - stageStart; }

    /** Converts refreshInterval ticks to ms. Zero ticks = 1 tick minimum (50 ms). */
    private long interval() {
        int ri = refreshInterval.get();
        return ri <= 0 ? 50L : ri * 50L;
    }

    private void dbg(String fmt, Object... args) {
        if (debug.get()) info("[dbg] " + fmt, args);
    }

    private boolean isDeliverable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.isOf(deliverItem.get())) return true;
        return shulkerMode.get() && isShulkerBox(stack) && shulkerContainsTarget(stack);
    }

    private boolean hasDeliverableItems() {
        for (int i = 0; i < 36; i++) {
            if (isDeliverable(mc.player.getInventory().getStack(i))) return true;
        }
        return false;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem().getTranslationKey().endsWith("shulker_box");
    }

    /**
     * Uses DataComponentTypes.CONTAINER to read shulker contents directly.
     * This is authoritative — unlike tooltip parsing, it works regardless of
     * TooltipType and correctly returns false for empty shulkers.
     */
    private boolean shulkerContainsTarget(ItemStack shulker) {
        ContainerComponent c = shulker.get(DataComponentTypes.CONTAINER);
        if (c == null) return false;
        Item target = deliverItem.get();
        for (ItemStack s : c.iterateNonEmpty()) {
            if (s.isOf(target)) return true;
        }
        return false;
    }

    private static boolean isConfirmGlass(ItemStack stack) {
        return !stack.isEmpty()
            && (stack.isOf(Items.LIME_STAINED_GLASS_PANE)
                || stack.isOf(Items.GREEN_STAINED_GLASS_PANE));
    }

    private static int findGuiSlotId(ScreenHandler h, PlayerInventory inv, int invIndex) {
        for (Slot s : h.slots) {
            if (s.inventory == inv && s.getIndex() == invIndex) return s.id;
        }
        return -1;
    }

    private boolean isBlacklisted(String name) {
        if (name == null || blacklistedPlayers.get().isEmpty()) return false;
        return blacklistedPlayers.get().stream().anyMatch(b -> b.equalsIgnoreCase(name));
    }

    private boolean isFilteredByAdminList(String seller) {
        AdminListModule.Role role = adminListRole.get();
        if (role == AdminListModule.Role.OFF) return false;
        AdminListModule al = Modules.get().get(AdminListModule.class);
        if (al == null || !al.isActive()) return false;
        boolean isAdmin = al.isAdmin(seller);
        return role == AdminListModule.Role.BLACKLIST ? isAdmin : !isAdmin;
    }

    private void pruneRecentOrders(long now) {
        recentOrders.entrySet().removeIf(e -> now - e.getValue() > 2_000);
    }

    // Price extraction — patterns precompiled as constants
    private static final List<Pattern> PRICE_PATTERNS = List.of(
        Pattern.compile("\\$([0-9,]+(?:\\.[0-9]*)?)([kmb])?",             Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:price|pay|reward)\\s*:\\s*([0-9,]+(?:\\.[0-9]*)?)([kmb])?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("([0-9,]+(?:\\.[0-9]*)?)([kmb])?\\s*coins?",      Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b([0-9,]+(?:\\.[0-9]*)?)([kmb])\\b",           Pattern.CASE_INSENSITIVE)
    );

    private double extractPrice(ItemStack stack) {
        List<Text> tt = stack.getTooltip(
            Item.TooltipContext.create(mc.world), mc.player,
            net.minecraft.item.tooltip.TooltipType.BASIC);
        for (Text line : tt) {
            String raw = line.getString().replace(",", "");
            for (Pattern p : PRICE_PATTERNS) {
                Matcher m = p.matcher(raw);
                if (m.find()) {
                    try {
                        double base = Double.parseDouble(m.group(1));
                        String sfx  = m.group(2) != null ? m.group(2).toLowerCase() : "";
                        return switch (sfx) {
                            case "k" -> base * 1_000;
                            case "m" -> base * 1_000_000;
                            case "b" -> base * 1_000_000_000;
                            default  -> base;
                        };
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return -1;
    }

    private static final Pattern SELLER_RE =
        Pattern.compile("(?i)(?:player|from|by|seller|owner)\\s*:\\s*([a-zA-Z0-9_]{3,16})");

    private String extractPlayerName(ItemStack stack) {
        List<Text> tt = stack.getTooltip(
            Item.TooltipContext.create(mc.world), mc.player,
            net.minecraft.item.tooltip.TooltipType.BASIC);
        for (Text line : tt) {
            Matcher m = SELLER_RE.matcher(line.getString());
            if (m.find()) return m.group(1);
        }
        return null;
    }

    static double parsePrice(String s) {
        if (s == null || s.isBlank()) return -1;
        String c = s.trim().toLowerCase().replace(",", "");
        double mult = 1;
        if      (c.endsWith("b")) { mult = 1e9; c = c.substring(0, c.length() - 1); }
        else if (c.endsWith("m")) { mult = 1e6; c = c.substring(0, c.length() - 1); }
        else if (c.endsWith("k")) { mult = 1e3; c = c.substring(0, c.length() - 1); }
        try { return Double.parseDouble(c) * mult; } catch (NumberFormatException e) { return -1; }
    }

    private static String formatPrice(double p) {
        if (p >= 1e9) return String.format("$%.1fB", p / 1e9);
        if (p >= 1e6) return String.format("$%.1fM", p / 1e6);
        if (p >= 1e3) return String.format("$%.1fK", p / 1e3);
        return String.format("$%.0f", p);
    }

    @Override
    public String getInfoString() {
        if (!isActive()) return null;
        if (lastSeller != null) return stage.name() + " | " + lastSeller;
        return stage.name();
    }
}
