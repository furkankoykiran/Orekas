package com.webrekas.addon.modules;

import com.webrekas.addon.OrekasAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.*;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills and confirms DonutSMP player orders automatically.
 *
 * Improvements over the Glazed reference:
 *  - One inventory slot transferred per tick (QUICK_MOVE only) rather than
 *    the broken PICKUP+PICKUP_ALL+QUICK_MOVE spam that leaves the cursor dirty.
 *  - Every stage has a configurable watchdog timeout that cycles instead of
 *    hard-stopping the module.
 *  - Price patterns are precompiled static constants (not rebuilt each call).
 *  - Shulker detection uses translation-key suffix instead of an explicit
 *    17-item enum list.
 *  - `stage` / `stageStart` are volatile so getInfoString() is race-free.
 *  - The GUI sync-ID switch in WAIT_DEPOSIT correctly detects the deposit GUI
 *    even if the orders GUI is still briefly visible.
 */
public class OrderSniperModule extends Module {

    // Per-stage watchdog timeouts (ms)
    private static final long T_WAIT_GUI      = 3_000;
    private static final long T_SCAN          = 4_000;
    private static final long T_WAIT_DEPOSIT  = 4_000;
    private static final long T_WAIT_CONFIRM  = 4_000;
    private static final long T_CONFIRM       = 3_000;

    // ── State machine ─────────────────────────────────────────────────────────

    private enum Stage {
        IDLE, REFRESH, OPEN_ORDERS, WAIT_GUI, SCAN_ORDERS,
        WAIT_DEPOSIT, DRAIN_INVENTORY, WAIT_CONFIRM, CONFIRM_SALE,
        FINALIZE, CYCLE_PAUSE
    }

    /** Written by game thread, read by render thread (getInfoString). */
    private volatile Stage stage      = Stage.IDLE;
    private volatile long  stageStart = 0L;

    private int ordersGUISyncId  = -1;  // syncId of the /orders listing GUI
    private int depositGUISyncId = -1;  // syncId of the deposit GUI
    private int drainCursor      = 0;   // next inventory index to attempt transfer

    // ── Settings ──────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgBlacklist = settings.createGroup("Blacklist");

    private final Setting<String> searchQuery = sgGeneral.add(new StringSetting.Builder()
        .name("search-query")
        .description("Argument passed to /orders <query>.")
        .defaultValue("diamond")
        .build());

    private final Setting<Item> deliverItem = sgGeneral.add(new ItemSetting.Builder()
        .name("deliver-item")
        .description("Item type to deliver to matched orders.")
        .defaultValue(Items.DIAMOND)
        .build());

    private final Setting<String> minPrice = sgGeneral.add(new StringSetting.Builder()
        .name("min-price")
        .description("Minimum order price to accept (e.g. 100, 2.5k, 1m, 1b).")
        .defaultValue("1")
        .build());

    private final Setting<Boolean> shulkerMode = sgGeneral.add(new BoolSetting.Builder()
        .name("shulker-mode")
        .description("Also deliver shulker boxes that contain the target item.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Extra tick delay between actions (raise on high-latency connections).")
        .defaultValue(2)
        .min(0).max(20).sliderMax(10)
        .build());

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show status messages in chat.")
        .defaultValue(true)
        .build());

    private final Setting<List<String>> blacklistedPlayers = sgBlacklist.add(new StringListSetting.Builder()
        .name("blacklisted-players")
        .description("Orders from these players will be skipped.")
        .defaultValue(List.of())
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
            error("Invalid min-price. Use a number with optional k / m / b suffix.");
            toggle();
            return;
        }
        ordersGUISyncId = depositGUISyncId = -1;
        drainCursor = 0;
        advance(Stage.REFRESH);
    }

    @Override
    public void onDeactivate() {
        stage = Stage.IDLE;
    }

    // ── Tick handler ──────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();

        switch (stage) {
            case REFRESH         -> tickRefresh(now);
            case OPEN_ORDERS     -> tickOpenOrders(now);
            case WAIT_GUI        -> tickWaitGui(now);
            case SCAN_ORDERS     -> tickScanOrders(now);
            case WAIT_DEPOSIT    -> tickWaitDeposit(now);
            case DRAIN_INVENTORY -> tickDrain(now);
            case WAIT_CONFIRM    -> tickWaitConfirm(now);
            case CONFIRM_SALE    -> tickConfirm(now);
            case FINALIZE        -> tickFinalize(now);
            case CYCLE_PAUSE     -> { if (elapsed(now) >= delayMs(5)) advance(Stage.REFRESH); }
            default              -> {}
        }
    }

    // ── Stage implementations ─────────────────────────────────────────────────

    private void tickRefresh(long now) {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            // Slot 49 = refresh / navigation button in DonutSMP orders GUI (6-row chest)
            mc.interactionManager.clickSlot(
                screen.getScreenHandler().syncId, 49, 1, SlotActionType.QUICK_MOVE, mc.player);
            if (elapsed(now) > 100) advance(Stage.OPEN_ORDERS);
        } else {
            advance(Stage.OPEN_ORDERS);
        }
    }

    private void tickOpenOrders(long now) {
        ChatUtils.sendPlayerMsg("/orders " + searchQuery.get());
        advance(Stage.WAIT_GUI);
    }

    private void tickWaitGui(long now) {
        // Require a minimum settling delay before reading the GUI
        if (elapsed(now) < delayMs(Math.max(8, delayTicks.get()))) return;

        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            ordersGUISyncId = screen.getScreenHandler().syncId;
            advance(Stage.SCAN_ORDERS);
        } else if (elapsed(now) > T_WAIT_GUI) {
            advance(Stage.OPEN_ORDERS); // retry
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

            String seller = extractPlayerName(stack);
            if (isBlacklisted(seller)) continue;

            mc.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
            if (notifications.get()) {
                info("Sniping order%s for %s",
                    seller != null ? " from " + seller : "",
                    formatPrice(price));
            }
            ordersGUISyncId = h.syncId;
            advance(Stage.WAIT_DEPOSIT);
            return;
        }

        // No acceptable order – nudge the refresh button and restart
        if (elapsed(now) > T_SCAN) {
            mc.interactionManager.clickSlot(h.syncId, 49, 1, SlotActionType.QUICK_MOVE, mc.player);
            advance(Stage.OPEN_ORDERS);
        }
    }

    private void tickWaitDeposit(long now) {
        if (mc.currentScreen instanceof GenericContainerScreen screen) {
            int id = screen.getScreenHandler().syncId;
            // Server has opened the deposit GUI when the syncId changes
            if (id != ordersGUISyncId) {
                depositGUISyncId = id;
                drainCursor = 0;
                advance(Stage.DRAIN_INVENTORY);
                return;
            }
        }
        if (elapsed(now) > T_WAIT_DEPOSIT) {
            if (mc.currentScreen != null) mc.player.closeHandledScreen();
            advance(Stage.OPEN_ORDERS);
        }
    }

    /**
     * Transfers one deliverable inventory slot per tick via QUICK_MOVE.
     * Using one transfer per tick avoids flooding the server with packets
     * and gives the server time to acknowledge each move before the next.
     */
    private void tickDrain(long now) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            advance(Stage.WAIT_CONFIRM);
            return;
        }
        ScreenHandler h = screen.getScreenHandler();
        PlayerInventory playerInv = mc.player.getInventory();

        // Stop draining if the deposit chest side is full
        boolean chestHasSpace = h.slots.stream()
            .anyMatch(s -> s.inventory != playerInv && s.getStack().isEmpty());
        if (!chestHasSpace) {
            mc.player.closeHandledScreen();
            advance(Stage.WAIT_CONFIRM);
            return;
        }

        // Skip non-deliverable slots
        while (drainCursor < 36 && !isDeliverable(playerInv.getStack(drainCursor))) {
            drainCursor++;
        }

        if (drainCursor >= 36) {
            // Entire inventory scanned – close and confirm
            mc.player.closeHandledScreen();
            advance(Stage.WAIT_CONFIRM);
            return;
        }

        // Transfer this slot and advance the cursor for next tick
        int guiSlotId = findGuiSlotId(h, playerInv, drainCursor);
        if (guiSlotId >= 0) {
            mc.interactionManager.clickSlot(
                h.syncId, guiSlotId, 0, SlotActionType.QUICK_MOVE, mc.player);
        }
        drainCursor++;
    }

    private void tickWaitConfirm(long now) {
        if (elapsed(now) < delayMs(Math.max(8, delayTicks.get()))) return;

        if (mc.currentScreen instanceof GenericContainerScreen) {
            advance(Stage.CONFIRM_SALE);
        } else if (elapsed(now) > T_WAIT_CONFIRM) {
            // Do not stop; cycle back so the next order is attempted
            advance(Stage.CYCLE_PAUSE);
        }
    }

    private void tickConfirm(long now) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) {
            if (elapsed(now) > T_CONFIRM) advance(Stage.CYCLE_PAUSE);
            return;
        }
        ScreenHandler h = screen.getScreenHandler();
        for (Slot slot : h.slots) {
            if (isConfirmGlass(slot.getStack())) {
                // Click twice for redundancy on high-latency connections
                mc.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                advance(Stage.FINALIZE);
                return;
            }
        }
        if (elapsed(now) > T_CONFIRM) advance(Stage.CYCLE_PAUSE);
    }

    private void tickFinalize(long now) {
        if (elapsed(now) < delayMs(Math.max(5, delayTicks.get()))) return;
        if (mc.currentScreen != null) mc.player.closeHandledScreen();

        if (!hasDeliverableItems()) {
            if (notifications.get()) info("No more items to deliver. Stopping.");
            toggle();
        } else {
            advance(Stage.CYCLE_PAUSE);
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private void advance(Stage next) {
        stage      = next;
        stageStart = System.currentTimeMillis();
    }

    private long elapsed(long now)   { return now - stageStart; }
    private long delayMs(int ticks)  { return ticks * 50L; }

    private boolean isDeliverable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.isOf(deliverItem.get())) return true;
        return shulkerMode.get() && isShulkerBox(stack) && shulkerHasTarget(stack);
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

    private boolean shulkerHasTarget(ItemStack shulker) {
        String name = deliverItem.get().getName().getString().toLowerCase();
        List<Text> tt = shulker.getTooltip(
            Item.TooltipContext.create(mc.world), mc.player, TooltipType.BASIC);
        for (Text line : tt) {
            String t = line.getString().toLowerCase();
            if (t.contains(name) || t.contains(name.replace(" ", "_"))) return true;
        }
        return false;
    }

    private static boolean isConfirmGlass(ItemStack stack) {
        return !stack.isEmpty()
            && (stack.isOf(Items.LIME_STAINED_GLASS_PANE)
                || stack.isOf(Items.GREEN_STAINED_GLASS_PANE));
    }

    /**
     * Resolves the GUI slot ID for a player inventory index (0-35).
     * Uses inventory identity comparison so it works regardless of chest size.
     */
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

    // Price patterns – precompiled to avoid per-call regex compilation
    private static final List<Pattern> PRICE_PATTERNS = List.of(
        Pattern.compile("\\$([0-9,]+(?:\\.[0-9]*)?)([kmb])?",             Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:price|pay|reward)\\s*:\\s*([0-9,]+(?:\\.[0-9]*)?)([kmb])?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("([0-9,]+(?:\\.[0-9]*)?)([kmb])?\\s*coins?",      Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b([0-9,]+(?:\\.[0-9]*)?)([kmb])\\b",           Pattern.CASE_INSENSITIVE)
    );

    private double extractPrice(ItemStack stack) {
        List<Text> tt = stack.getTooltip(
            Item.TooltipContext.create(mc.world), mc.player, TooltipType.BASIC);
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
            Item.TooltipContext.create(mc.world), mc.player, TooltipType.BASIC);
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
        return isActive() ? stage.name() : null;
    }
}
